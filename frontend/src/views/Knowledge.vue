<template>
  <section v-if="me.operator" id="knowledge" class="knowledge-ops">
    <p class="err" v-if="error">{{ error }}</p>
    <div class="row knowledge-actions">
      <label class="btn btn-ghost">
        上传 Markdown
        <input class="file-hidden" type="file" accept=".md,.markdown,.txt,text/markdown" @change="onFile" />
      </label>
      <button class="btn btn-ghost" type="button" :disabled="busy" @click="reindex">
        {{ busy === 'reindex' ? '正在重建…' : '重建索引' }}
      </button>
      <button
        class="btn btn-ghost"
        type="button"
        data-testid="knowledge-show-all"
        @click="toggleCatalog"
      >
        {{ showAll ? '收起目录' : '查看全部' }}
      </button>
    </div>

    <div class="settings-hub knowledge-groups" data-testid="knowledge-overview">
      <article class="settings-card knowledge-card" v-for="group in groups" :key="group.id">
        <p class="panel-label">{{ group.title }}</p>
        <h3>{{ group.summary }}</h3>
        <p class="muted">{{ (group.highlights || []).join(' · ') }}</p>
      </article>
    </div>

    <section v-if="showAll" class="panel" data-testid="knowledge-catalog">
      <div class="row knowledge-catalog-head">
        <label class="zy-select-wrap">
          <span class="muted">目录</span>
          <select
            class="zy-select zy-select-compact"
            v-model="filter"
            aria-label="知识目录筛选"
          >
            <option v-for="opt in filters" :key="opt.id" :value="opt.id">{{ opt.label }}</option>
          </select>
        </label>
        <p class="muted">{{ visibleDocs.length }} 篇 · 点开看标题与短摘要，全文交给审校 RAG</p>
      </div>
      <div class="zy-select-chips knowledge-dir" role="list">
        <button
          v-for="row in visibleDocs"
          :key="docKey(row)"
          type="button"
          class="zy-select-option"
          role="listitem"
          :class="{ on: selectedKey === docKey(row) }"
          @click="selectRow(row)"
        >
          <span>{{ row.title || row.filename }}</span>
          <span class="muted knowledge-file">{{ row.filename }}</span>
        </button>
      </div>
      <div v-if="selected" class="knowledge-preview" data-testid="knowledge-preview">
        <p class="panel-label">{{ selected.filename }}</p>
        <h3>{{ preview.title }}</h3>
        <p class="muted">{{ preview.summary }}</p>
        <p v-if="preview.sections.length" class="muted">专章 {{ preview.sections.join(' · ') }}</p>
        <button
          v-if="selected.id"
          class="text-link"
          type="button"
          :disabled="busy"
          @click="remove(selected)"
        >删除这份上传</button>
      </div>
      <p v-else class="muted knowledge-preview-hint">点上面某一条，看标题和短摘要。</p>
    </section>

    <p v-if="(catalog.uploaded || []).length && !showAll" class="muted">
      已上传 {{ catalog.uploaded.length }} 份运营规范，点「查看全部」可删。
    </p>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import {
  CATALOG_FILTERS,
  catalogDocs,
  docKey,
  overviewGroups,
  previewOf
} from '../knowledge-overview'
import { toast } from '../toast'

const props = defineProps({
  me: { type: Object, default: () => ({}) }
})

const catalog = ref({ bundled: [], uploaded: [], publicChunks: 0, groups: [] })
const error = ref('')
const busy = ref('')
const showAll = ref(false)
const filter = ref('all')
const selectedKey = ref('')
const filters = CATALOG_FILTERS

onMounted(load)
watch(() => props.me.operator, (on) => {
  if (on) load()
})

const groups = computed(() => overviewGroups(catalog.value))
const visibleDocs = computed(() => catalogDocs(catalog.value, filter.value))
const selected = computed(() => visibleDocs.value.find((row) => docKey(row) === selectedKey.value) || null)
const preview = computed(() => previewOf(selected.value))

watch(visibleDocs, (rows) => {
  if (selectedKey.value && !rows.some((row) => docKey(row) === selectedKey.value)) {
    selectedKey.value = ''
  }
})

function toggleCatalog() {
  showAll.value = !showAll.value
  if (!showAll.value) {
    selectedKey.value = ''
  }
}

function selectRow(row) {
  const key = docKey(row)
  selectedKey.value = selectedKey.value === key ? '' : key
}

async function load() {
  if (!props.me.operator) return
  error.value = ''
  try {
    catalog.value = await api('/ops/knowledge')
  } catch (e) {
    error.value = e.message || '无法读取公共知识'
  }
}

async function onFile(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  busy.value = 'upload'
  error.value = ''
  const fd = new FormData()
  fd.append('file', file)
  try {
    catalog.value = await api('/ops/knowledge', { method: 'POST', body: fd })
    toast('已上传并重建公共索引')
  } catch (err) {
    error.value = err.message || '上传失败'
    toast(error.value, 'err')
  } finally {
    busy.value = ''
  }
}

async function reindex() {
  busy.value = 'reindex'
  error.value = ''
  try {
    catalog.value = await api('/ops/knowledge/reindex', { method: 'POST', body: {} })
    toast('公共索引已重建')
  } catch (err) {
    error.value = err.message || '重建失败'
    toast(error.value, 'err')
  } finally {
    busy.value = ''
  }
}

async function remove(row) {
  if (!row?.id) return
  busy.value = 'del'
  error.value = ''
  try {
    catalog.value = await api('/ops/knowledge/' + row.id, { method: 'DELETE' })
    selectedKey.value = ''
    toast('已删除并重建公共索引')
  } catch (err) {
    error.value = err.message || '删除失败'
    toast(error.value, 'err')
  } finally {
    busy.value = ''
  }
}
</script>

<style scoped>
.knowledge-ops { margin-top: 4px; }
.knowledge-actions { margin: 0 0 18px; }
.knowledge-groups { margin-bottom: 18px; }
.knowledge-card {
  cursor: default;
  min-height: 132px;
}
.knowledge-card h3 {
  font-size: 16px;
  line-height: 1.45;
  font-weight: 600;
}
.knowledge-catalog-head {
  justify-content: space-between;
  margin-bottom: 12px;
}
.knowledge-dir {
  max-height: min(420px, 58vh);
  gap: 4px;
}
.knowledge-file {
  display: block;
  font-weight: 500;
  margin-top: 2px;
}
.zy-select-option.on .knowledge-file {
  color: rgba(255, 252, 247, 0.72);
}
.knowledge-preview {
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: rgba(255, 252, 247, 0.55);
}
.knowledge-preview h3 { margin: 0 0 8px; }
.knowledge-preview .text-link {
  margin-top: 10px;
  background: transparent;
  border: 0;
  padding: 0;
  font: inherit;
}
.knowledge-preview-hint { margin: 12px 0 0; }
</style>
