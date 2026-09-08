<template>
  <div class="models-block">
    <p class="page-lead" style="margin-bottom:16px">
      点开下面某一个论文 Agent，就可以改成平台默认，或填你自己的 API Key。
      云笺和检索（embedding / rerank）由平台提供，不可更换。
    </p>
    <p class="err" v-if="loadError">{{ loadError }}</p>

    <section class="panel">
      <p class="panel-label">平台默认</p>
      <h3>{{ catalog.platform?.live ? '已接通平台模型' : '当前为本地规则模式' }}</h3>
      <dl class="account-kv" style="margin-top:12px">
        <dt>论文审校</dt><dd>{{ catalog.platform?.paperModel || '—' }}</dd>
        <dt>图表视觉</dt><dd>{{ catalog.platform?.visionModel || '—' }}</dd>
      </dl>
    </section>

    <div class="grid-2">
      <section class="panel model-locked">
        <p class="panel-label">云笺</p>
        <h3>{{ catalog.locked?.cs?.name || '云笺' }}</h3>
        <p class="muted">{{ catalog.locked?.cs?.note || '由平台提供，不可更换' }}</p>
        <p style="margin-top:10px">{{ catalog.locked?.cs?.model || '—' }}</p>
      </section>
      <section class="panel model-locked">
        <p class="panel-label">检索 RAG</p>
        <h3>{{ catalog.locked?.rag?.name || '检索' }}</h3>
        <p class="muted">{{ catalog.locked?.rag?.note || '由平台提供，不可更换' }}</p>
        <p style="margin-top:10px">
          {{ catalog.locked?.rag?.embeddingModel || '—' }} · {{ catalog.locked?.rag?.rerankModel || '—' }}
        </p>
      </section>
    </div>

    <section class="panel">
      <p class="panel-label">计费</p>
      <h3>自备 Key 只收技能费</h3>
      <p class="muted">{{ catalog.skillFee?.note || '自备模型时 token 走你自己的云账单，平台只收技能与提示词服务费。' }}</p>
    </section>

    <section class="panel model-card" v-for="agent in agents" :key="agent.id">
      <button type="button" class="model-card-head" @click="toggle(agent)">
        <div class="model-card-meta">
          <p class="panel-label">{{ agent.name }}</p>
          <h3>{{ agent.summary }}</h3>
        </div>
        <span class="model-card-toggle">{{ openId === agent.id ? '收起' : '配置' }}</span>
      </button>
      <div class="model-body" v-if="openId === agent.id">
        <div class="filter-row" style="margin-top:0;margin-bottom:8px">
          <button type="button" class="fmt-pill" :class="{ on: draftOf(agent).mode !== 'BYOK' }" @click.stop="usePlatform(agent)">使用平台默认</button>
          <button type="button" class="fmt-pill" :class="{ on: draftOf(agent).mode === 'BYOK' }" @click.stop="startByok(agent)">接通我的 API Key</button>
        </div>
        <div v-if="draftOf(agent).mode === 'BYOK'" class="model-form">
          <label>
            <span>提供商</span>
            <span class="zy-select-wrap block">
              <select class="field zy-select" v-model="draftOf(agent).provider" @change="onProvider(agent)">
                <option v-for="p in catalog.providers || []" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
            </span>
          </label>
          <label>
            <span>Base URL</span>
            <input class="field" v-model="draftOf(agent).baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
          </label>
          <label>
            <span>模型 ID</span>
            <input class="field" v-model="draftOf(agent).modelId" placeholder="例如 qwen-plus" />
          </label>
          <label>
            <span>API Key</span>
            <input class="field" v-model="draftOf(agent).apiKey" type="password" :placeholder="agent.apiKeyMasked || '只写不读回全文'" autocomplete="off" />
          </label>
          <p class="muted" v-if="agent.apiKeyMasked">当前已保存 {{ agent.apiKeyMasked }}。留空则保留原 Key。</p>
          <div class="row">
            <button class="btn btn-accent" type="button" :disabled="savingId === agent.id" @click.stop="save(agent)">
              {{ savingId === agent.id ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
        <p class="muted" v-else>运行时使用平台 {{ catalog.platform?.paperModel }}。</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { toast } from '../toast'

const FALLBACK_AGENTS = [
  { id: 'CITATION_INTEGRITY', name: '引用核验', summary: '核对参考文献与 DOI。', mode: 'PLATFORM' },
  { id: 'FIGURE_PDF', name: '图表检查', summary: '看图与版式，必要时走视觉模型。', mode: 'PLATFORM' },
  { id: 'ACADEMIC_REVIEWER', name: '学术审稿', summary: '按主张取证，不空口下结论。', mode: 'PLATFORM' },
  { id: 'ACADEMIC_STYLE', name: '语言润色', summary: '去套话，保留数字与引用。', mode: 'PLATFORM' },
  { id: 'REVISION_PLANNING', name: '改稿计划', summary: '把问题拆成可执行的修改任务。', mode: 'PLATFORM' },
  { id: 'REVISION_EXECUTION', name: '修改执行', summary: '只写候选稿，不覆盖正式稿。', mode: 'PLATFORM' },
  { id: 'FINAL_VERIFICATION', name: '结果复核', summary: '独立核验，不采信执行自述。', mode: 'PLATFORM' }
]

const catalog = ref({ agents: FALLBACK_AGENTS, platform: {}, locked: {}, skillFee: {}, providers: [] })
const drafts = reactive({})
const openId = ref('')
const savingId = ref('')
const loadError = ref('')

const agents = computed(() => (catalog.value.agents?.length ? catalog.value.agents : FALLBACK_AGENTS))

onMounted(load)

function draftOf(agent) {
  if (!drafts[agent.id]) {
    drafts[agent.id] = blankDraft(agent)
  }
  return drafts[agent.id]
}

function blankDraft(agent) {
  return {
    mode: agent.mode || 'PLATFORM',
    provider: agent.provider && agent.provider !== 'platform' ? agent.provider : '',
    baseUrl: agent.baseUrl || '',
    modelId: agent.modelId || '',
    apiKey: ''
  }
}

function applyCatalog(data) {
  catalog.value = data && typeof data === 'object' ? data : catalog.value
  const list = catalog.value.agents || []
  list.forEach((agent) => {
    drafts[agent.id] = blankDraft(agent)
  })
}

async function load() {
  loadError.value = ''
  try {
    applyCatalog(await api('/models'))
  } catch (first) {
    try {
      applyCatalog(await api('/me/models'))
    } catch (e) {
      loadError.value = e.message || first.message || '模型目录加载失败'
      catalog.value = {
        ...catalog.value,
        agents: FALLBACK_AGENTS,
        locked: catalog.value.locked || {
          cs: { name: '云笺', note: '由平台提供，不可更换' },
          rag: { name: '检索', note: '由平台提供，不可更换' }
        }
      }
    }
  }
}

function toggle(agent) {
  openId.value = openId.value === agent.id ? '' : agent.id
  draftOf(agent)
}

function onProvider(agent) {
  const draft = draftOf(agent)
  const p = (catalog.value.providers || []).find((x) => x.id === draft.provider)
  if (p?.baseUrl) draft.baseUrl = p.baseUrl
}

function startByok(agent) {
  openId.value = agent.id
  const draft = draftOf(agent)
  draft.mode = 'BYOK'
  if (!draft.provider || draft.provider === 'platform') {
    const first = (catalog.value.providers || [])[0]
    draft.provider = first?.id || 'dashscope'
    draft.baseUrl = draft.baseUrl || first?.baseUrl || ''
  }
}

async function usePlatform(agent) {
  openId.value = agent.id
  savingId.value = agent.id
  try {
    applyCatalog(await api('/models/' + agent.id, { method: 'PUT', body: { mode: 'PLATFORM' } }))
    toast(agent.name + ' 已改回平台默认')
  } catch (e) {
    toast(e.message || '保存失败', 'err')
  } finally {
    savingId.value = ''
  }
}

async function save(agent) {
  const draft = draftOf(agent)
  savingId.value = agent.id
  try {
    applyCatalog(await api('/models/' + agent.id, {
      method: 'PUT',
      body: {
        mode: 'BYOK',
        provider: draft.provider,
        baseUrl: draft.baseUrl,
        modelId: draft.modelId,
        apiKey: draft.apiKey || ''
      }
    }))
    toast(agent.name + ' 已接通你的模型')
  } catch (e) {
    toast(e.message || '保存失败', 'err')
  } finally {
    savingId.value = ''
  }
}
</script>
