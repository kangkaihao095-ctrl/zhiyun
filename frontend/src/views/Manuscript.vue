<template>
  <div v-if="detail.manuscript">
    <p class="page-kicker">论文</p>
    <h1 class="page-title">{{ detail.manuscript.title }}</h1>
    <p class="page-lead">
      当前正式稿是第 {{ detail.manuscript.currentVersion }} 版。可以先预览正文，再选择检查范围。修改建议不会直接覆盖正式稿。
    </p>
    <p class="page-ids">
      <CopyId label="稿件 ID" :value="detail.manuscript.id" />
      <CopyId v-if="preview.versionNo" label="版本号" :value="preview.versionNo" />
    </p>

    <div class="panel">
      <p class="panel-label">打开 / 预览</p>
      <div class="ver-toolbar">
        <h3>{{ previewHeading }}</h3>
        <div class="row">
          <button
            v-if="preview.hasFile"
            class="btn btn-accent"
            type="button"
            @click="openFile(false)"
          >打开原件</button>
          <button
            v-if="preview.hasFile"
            class="btn btn-ghost"
            type="button"
            @click="openFile(true)"
          >下载</button>
        </div>
      </div>
      <PdfInspectPanel v-if="inspect.pagePreview" :inspect="inspect" @open="openFile(false)" />
      <p class="muted" v-if="preview.format === 'PDF'">
        下面是抽出的文本。页规格和图表见上方对照面；点「打开原件」看 PDF 本身。
      </p>
      <p class="muted" v-else-if="preview.format === 'DOCX'">
        这是 Word 抽出的文本。也可以下载原件。Word 没有 PDF 页预览。
      </p>
      <p class="muted" v-else-if="preview.format === 'MD'">
        这是 Markdown 抽出的正文。不是 PDF，不展示页规格。
      </p>
      <div v-if="preview.contentText" class="ms-body ms-preview" v-html="renderBody(preview.contentText)" />
      <div v-else class="empty-block">这篇还没有抽出正文。若已上传原件，可点「打开原件」。 </div>
    </div>

    <div class="panel">
      <p class="panel-label">检查范围</p>
      <h3>这次要检查什么</h3>
      <div class="wf-grid">
        <button
          v-for="w in workflows"
          :key="w.id"
          class="wf-card"
          :class="{ on: workflow === w.id }"
          type="button"
          @click="workflow = w.id"
        >
          <div class="wf-show-top">
            <strong>{{ w.name }}</strong>
            <span class="wf-cap" :class="{ on: workflow === w.id }">最多 {{ w.capPoints }} 额度</span>
          </div>
          <span>{{ w.summary }}</span>
        </button>
      </div>

      <div v-if="selected" class="wf-detail">
        <p class="wf-sum">{{ selected.useWhen }}</p>
        <div class="pipe" aria-label="审校步骤">
          <template v-for="(a, i) in selected.agents" :key="selected.id + a">
            <span class="pipe-node">{{ a }}</span>
            <span v-if="i < selected.agents.length - 1" class="pipe-join">→</span>
          </template>
        </div>
      </div>

      <label class="panel-label" style="margin-top:16px;display:block">投稿期刊（可选）</label>
      <input
        class="field"
        v-model="venueQ"
        type="search"
        placeholder="搜索 IEEE / ACL / Nature / ICML…"
        aria-label="搜索投稿期刊"
      />
      <label class="zy-select-wrap block" style="margin-top:8px">
        <select class="field zy-select" v-model="targetVenue" aria-label="投稿期刊">
          <option value="">未指定（从正文抽刊名）</option>
          <option v-for="v in filteredVenues" :key="v.id" :value="v.id">{{ v.name }} · {{ v.family }}</option>
        </select>
      </label>
      <p class="muted" style="margin-top:8px">
        {{ venueHint }}
      </p>

      <div class="row" style="margin-top:16px">
        <button class="btn btn-accent" type="button" :disabled="starting" @click="start">
          {{ starting ? '正在开始…' : ('开始「' + (selected?.name || '审校') + '」') }}
        </button>
        <router-link class="btn btn-ghost" to="/history">以前的审校</router-link>
      </div>
      <p class="err" v-if="error">{{ error }}</p>
    </div>
    <div class="panel">
      <h3>版本</h3>
      <p class="muted">点某一版即可预览全文。正式稿是主干，候选稿是尚未接受的修改。</p>
      <button
        v-for="v in detail.versions"
        :key="v.id"
        class="ver-row"
        :class="{ on: preview.versionNo === v.versionNo }"
        type="button"
        @click="selectVersion(v)"
      >
        <span>第 {{ v.versionNo }} 版</span>
        <span class="ver-tag" :data-s="v.status">{{ versionStatusLabel(v.status) }}</span>
        <span v-if="v.current" class="ver-now">正在看的主干</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, fetchAuthBlob } from '../api'
import { toast } from '../toast'
import { versionStatusLabel } from '../labels'
import PdfInspectPanel from '../components/PdfInspectPanel.vue'
import CopyId from '../components/CopyId.vue'

const fallback = [
  { id: 'CITATION_ONLY', name: '引用核验', summary: '核对参考文献和 DOI 是否真实，不改正文。', agents: ['引用核验'], capPoints: 3, useWhen: '交稿前先查一遍文献。' },
  { id: 'QUICK_REVIEW', name: '快速审读', summary: '先查文献，再看文字是否空泛。不改正文。', agents: ['引用核验', '语言润色'], capPoints: 5, useWhen: '改完一章，想先看有哪些问题。' },
  { id: 'FULL_REVIEW', name: '完整审校', summary: '文献、图表、文字一起检查，并给出修改稿。是否采纳由你决定。', agents: ['引用核验', '图表检查', '学术审稿', '语言润色', '改稿计划', '修改执行', '结果复核'], capPoints: 10, useWhen: '交稿前需要一份完整的修改建议。' }
]

const route = useRoute()
const router = useRouter()
const detail = ref({})
const workflows = ref(fallback)
const workflow = ref('CITATION_ONLY')
const error = ref('')
const starting = ref(false)
const venues = ref([])
const venueQ = ref('')
const targetVenue = ref('')
const filteredVenues = computed(() => {
  const q = venueQ.value.trim().toLowerCase()
  const list = !q
    ? venues.value
    : venues.value.filter((v) => {
        const blob = `${v.id} ${v.name} ${v.family} ${v.summary || ''}`.toLowerCase()
        return blob.includes(q)
      })
  if (targetVenue.value && !list.some((v) => v.id === targetVenue.value)) {
    const keep = venues.value.find((v) => v.id === targetVenue.value)
    if (keep) return [keep, ...list]
  }
  return list
})
const venueHint = computed(() => {
  const hit = venues.value.find((v) => v.id === targetVenue.value)
  if (hit) return `审校将按 ${hit.name} 向公共知识库取规范。${hit.summary || ''}`
  return '未指定时，仍从标题和正文抽刊名；抽不到就用通用规范。'
})
const preview = ref({})
const inspect = ref({})
const selected = computed(() => workflows.value.find((w) => w.id === workflow.value) || workflows.value[0])
const previewHeading = computed(() => {
  const v = preview.value
  if (!v?.versionNo) return '当前正式稿'
  return `第 ${v.versionNo} 版 · ${versionStatusLabel(v.status)}`
})

onMounted(async () => {
  detail.value = await api('/manuscripts/' + route.params.id)
  pickCurrent()
  try {
    const data = await api('/workflows')
    if (data?.workflows?.length) workflows.value = data.workflows
  } catch {
    /* keep fallback catalog */
  }
  try {
    const data = await api('/venues')
    if (data?.venues?.length) venues.value = data.venues
  } catch {
    /* 目录失败时仍可未指定启动 */
  }
})

function pickCurrent() {
  const list = detail.value.versions || []
  const currentNo = detail.value.manuscript?.currentVersion
  const official = list.find((v) => v.current) || list.find((v) => v.versionNo === currentNo && v.status === 'OFFICIAL') || list.find((v) => v.status === 'OFFICIAL') || list[list.length - 1]
  preview.value = official || {}
  loadInspect(preview.value)
}

async function selectVersion(v) {
  preview.value = v
  loadInspect(v)
  if (v?.contentText) return
  try {
    preview.value = await api('/manuscripts/' + route.params.id + '/versions/' + v.versionNo)
    loadInspect(preview.value)
  } catch (e) {
    toast(e.message || '这一版打不开', 'err')
  }
}

async function loadInspect(v) {
  inspect.value = {}
  if (!v?.versionNo || v.format !== 'PDF') return
  try {
    inspect.value = await api('/manuscripts/' + route.params.id + '/versions/' + v.versionNo + '/inspect')
  } catch {
    inspect.value = { format: v.format, pagePreview: false }
  }
}

async function openFile(download) {
  const v = preview.value
  if (!v?.versionNo) return
  try {
    const blob = await fetchAuthBlob('/manuscripts/' + route.params.id + '/versions/' + v.versionNo + '/file' + (download ? '?download=true' : ''))
    const url = URL.createObjectURL(blob)
    if (download) {
      const a = document.createElement('a')
      a.href = url
      a.download = v.filename || detail.value.manuscript?.title || 'manuscript'
      a.click()
    } else {
      window.open(url, '_blank', 'noopener')
    }
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (e) {
    toast(e.message || '打不开原件', 'err')
  }
}

function escapeHtml(text) {
  return String(text || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function renderBody(text) {
  let html = escapeHtml(text)
  html = html.replace(/^#{1,6}[ \t]+(.+)$/gm, '<strong>$1</strong>')
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/`([^`]+)`/g, '<span class="msg-code">$1</span>')
  html = html.replace(/\n/g, '<br>')
  return html
}

async function start() {
  error.value = ''
  starting.value = true
  try {
    const task = await api('/manuscripts/' + route.params.id + '/reviews', {
      method: 'POST',
      body: { workflow: workflow.value, targetVenue: targetVenue.value || undefined }
    })
    toast('已开始「' + (selected.value?.name || '审校') + '」，结束后按实际用量扣额度')
    router.push('/reviews/' + task.id)
  } catch (e) {
    error.value = e.message
    toast(e.message, 'err')
  } finally {
    starting.value = false
  }
}
</script>
