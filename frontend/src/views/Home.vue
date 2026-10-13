<template>
  <div class="home-page papers-page">
    <section class="home-hero">
      <div class="home-hero-copy">
        <p class="page-kicker">论文</p>
        <h1 class="page-title">你好{{ me.displayName ? '，' + me.displayName : '' }}</h1>
        <p class="page-lead">课题用来分组和筛选稿件。先选定课题，再上传；下面的列表只显示当前课题里的论文。系统不会直接改你的原文。</p>
        <div class="home-stats">
          <div class="home-stat">
            <span class="home-stat-ico"><Icon name="folder" :size="16" /></span>
            <strong>{{ loaded ? projects.length : '–' }}</strong>
            <span>课题</span>
          </div>
          <div class="home-stat">
            <span class="home-stat-ico"><Icon name="papers" :size="16" /></span>
            <strong>{{ loaded ? msTotal : '–' }}</strong>
            <span>当前课题论文</span>
          </div>
          <div class="home-stat">
            <span class="home-stat-ico gold"><Icon name="coins" :size="16" /></span>
            <strong>{{ loaded ? (me.quota ?? 0) : '–' }}</strong>
            <span>剩余额度</span>
          </div>
        </div>
      </div>
      <ul class="home-pillars stagger">
        <li>
          <span class="pillar-ico"><Icon name="quote" /></span>
          <div>
            <strong>引用实查，不靠模型口算</strong>
            <p>Java 调 Crossref 核验 DOI 与元数据，查无实据直接标记 NOT_VERIFIED。</p>
          </div>
        </li>
        <li>
          <span class="pillar-ico"><Icon name="shield" /></span>
          <div>
            <strong>候选稿机制，正式稿由你做主</strong>
            <p>修改以候选稿逐处给出，全部接受、部分接受或不采纳，都由作者决定。</p>
          </div>
        </li>
        <li>
          <span class="pillar-ico"><Icon name="layers" /></span>
          <div>
            <strong>职责互斥的 Agent，结构化交接</strong>
            <p>审、改、证分离，复核独立于执行，不采信改稿方的自述。</p>
          </div>
        </li>
      </ul>
    </section>

    <section class="home-pipeline">
      <header>
        <div>
          <p class="panel-label">完整审校 · 7 个 Agent</p>
          <h3>从引用到复核，一条可追溯的审校流水线</h3>
        </div>
        <div class="home-pipeline-tags">
          <span>LiteFlow 编排</span>
          <span>RAG 投稿规范</span>
          <span>失败可续跑</span>
        </div>
      </header>
      <AgentPipeline mode="showcase" />
    </section>

    <ol class="home-steps" aria-label="开始一次审校">
      <li :class="{ done: !!projectId, now: !projectId }">
        <span class="step-n">{{ projectId ? '' : '1' }}<Icon v-if="projectId" name="check" :size="13" :stroke="2.6" /></span>
        <div><strong>选定课题</strong><em>{{ currentProject ? currentProject.name : '先新建一个课题' }}</em></div>
      </li>
      <li :class="{ done: manuscripts.length > 0, now: !!projectId && !manuscripts.length }">
        <span class="step-n">{{ manuscripts.length ? '' : '2' }}<Icon v-if="manuscripts.length" name="check" :size="13" :stroke="2.6" /></span>
        <div><strong>上传稿件</strong><em>PDF · Word · Markdown · TeX</em></div>
      </li>
      <li :class="{ now: manuscripts.length > 0 }">
        <span class="step-n">3</span>
        <div><strong>选择检查范围</strong><em>在论文页启动审校</em></div>
      </li>
    </ol>

    <div class="papers-stack">
      <section class="panel papers-section project-zone">
        <div class="zone-head">
          <span class="zone-ico"><Icon name="folder" /></span>
          <div>
            <p class="panel-label">课题</p>
            <h3>{{ currentProject ? ('当前课题：' + currentProject.name) : '还没有课题' }}</h3>
          </div>
        </div>
        <p class="muted">选中的课题会高亮。上传和论文列表都跟着当前课题走。</p>
        <div v-if="!loaded" class="skeleton-stack project-skeleton">
          <span class="skeleton" style="height:30px;width:70%" />
          <span class="skeleton" style="height:30px;width:52%" />
        </div>
        <div v-else-if="projects.length" class="project-chips" role="list">
          <button
            v-for="p in projects"
            :key="p.id"
            type="button"
            class="project-chip"
            :class="{ on: projectId === p.id }"
            :aria-pressed="projectId === p.id"
            data-testid="project-chip"
            @click="selectProject(p.id)"
          ><Icon name="folder" :size="13" />{{ p.name }}</button>
        </div>
        <div v-else class="empty-block">先建一个课题，再上传论文。</div>
        <div class="project-create">
          <input
            class="field"
            v-model="projectName"
            maxlength="80"
            placeholder="课题名称，例如 ACL 投稿"
            aria-label="新课题名称"
            @keyup.enter="createProject"
          />
          <button class="btn btn-ghost" type="button" @click="createProject"><Icon name="plus" :size="15" />新建课题</button>
        </div>
        <p class="muted project-required">名称必填。</p>
      </section>

      <section class="panel papers-section upload-zone" :class="{ dim: !projectId }">
        <div class="zone-head">
          <span class="zone-ico accent"><Icon name="upload" /></span>
          <div>
            <p class="panel-label">上传</p>
            <h3 v-if="currentProject">上传到「{{ currentProject.name }}」</h3>
            <h3 v-else>先建课题再上传</h3>
          </div>
        </div>
        <p v-if="currentProject" class="upload-target">
          当前课题 <strong>{{ currentProject.name }}</strong>
        </p>
        <p class="muted">PDF、Word、Markdown 或纯文本都可以。先点格式，或直接把文件拖进虚线框。</p>
        <div class="fmt-row">
          <button
            v-for="fmt in formats"
            :key="fmt.id"
            class="fmt-pill"
            :class="{ on: activeFmt === fmt.id }"
            type="button"
            @click="pick(fmt)"
          >{{ fmt.label }}</button>
        </div>
        <div
          class="dropzone"
          :class="{ over: dragging, ready: !!file }"
          @click="openPicker"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
        >
          <input
            ref="picker"
            class="file-hidden"
            type="file"
            :accept="accept"
            @change="onFile"
          />
          <span class="drop-ico"><Icon :name="file ? 'papers' : 'upload'" :size="22" /></span>
          <p class="drop-title">{{ file ? file.name : '拖入文件，或点这里选择' }}</p>
          <p class="muted">{{ file ? fileHint : 'PDF · Word · Markdown · TXT · TeX' }}</p>
        </div>
        <div class="row upload-actions">
          <button class="btn btn-accent" type="button" :disabled="!file || !projectId || uploading" @click="upload">
            <span v-if="uploading" class="spinner" aria-hidden="true" />
            {{ uploading ? '正在读取…' : '上传并打开' }}
          </button>
          <button v-if="file" class="btn btn-ghost" type="button" :disabled="uploading" @click="clearFile">换一份</button>
        </div>
        <p class="err" v-if="error">{{ error }}</p>
      </section>

      <section class="panel papers-section list-zone">
        <div class="zone-head">
          <span class="zone-ico"><Icon name="book" /></span>
          <div>
            <p class="panel-label">论文</p>
            <h3 v-if="currentProject">「{{ currentProject.name }}」里的论文</h3>
            <h3 v-else>我的论文</h3>
          </div>
        </div>
        <template v-if="projectId">
          <ListPager
            v-model:q="msQ"
            v-model:page="msPage"
            v-model:size="msSize"
            :total="msTotal"
            placeholder="按标题搜索"
            @search="searchManuscripts"
          />
          <div v-if="!manuscripts.length" class="empty-block">这个课题还没有论文。上传一份就能开始审校。</div>
          <div class="ms-list stagger">
            <router-link class="ms-card" v-for="m in manuscripts" :key="m.id" :to="'/manuscripts/' + m.id">
              <span class="ms-kind" :data-k="kind(m.title)">{{ kind(m.title) }}</span>
              <span class="ms-card-body">
                <strong>{{ m.title }}</strong>
                <span class="muted">第 {{ m.currentVersion }} 版 · 点开预览</span>
              </span>
              <span class="ms-go"><Icon name="arrowRight" :size="16" /></span>
            </router-link>
          </div>
        </template>
        <div v-else-if="!loaded" class="skeleton-stack">
          <span class="skeleton" style="height:64px" />
          <span class="skeleton" style="height:64px" />
        </div>
        <div v-else class="empty-block">先建课题再上传。</div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, asPage, DEFAULT_PAGE_SIZE, qs } from '../api'
import { toast } from '../toast'
import ListPager from '../components/ListPager.vue'
import Icon from '../components/Icon.vue'
import AgentPipeline from '../components/AgentPipeline.vue'

const router = useRouter()
const refreshMe = inject('refreshMe', () => Promise.resolve())
const formats = [
  { id: 'pdf', label: 'PDF', accept: '.pdf,application/pdf' },
  { id: 'docx', label: 'Word', accept: '.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document' },
  { id: 'md', label: 'Markdown', accept: '.md,.markdown,text/markdown' },
  { id: 'txt', label: 'TXT / TeX', accept: '.txt,.tex,text/plain,application/x-tex' }
]

const me = ref({})
const projects = ref([])
const manuscripts = ref([])
const msQ = ref('')
const msPage = ref(1)
const msSize = ref(DEFAULT_PAGE_SIZE)
const msTotal = ref(0)
const projectName = ref('')
const projectId = ref(null)
const file = ref(null)
const error = ref('')
const dragging = ref(false)
const uploading = ref(false)
const activeFmt = ref('pdf')
const picker = ref(null)
const loaded = ref(false)

const accept = computed(() => formats.find((f) => f.id === activeFmt.value)?.accept || '.pdf,.docx,.md,.txt,.tex')
const currentProject = computed(() => projects.value.find((p) => p.id === projectId.value) || null)
const fileHint = computed(() => {
  if (!file.value) return ''
  const kb = Math.max(1, Math.round(file.value.size / 1024))
  return kind(file.value.name) + ' · ' + kb + ' KB'
})

onMounted(load)
watch([msPage, msSize], loadManuscripts)

async function load() {
  try {
    me.value = await api('/me')
    projects.value = await api('/projects')
    if (projects.value.length && !projects.value.some((p) => p.id === projectId.value)) {
      projectId.value = projects.value[0].id
    }
    if (!projects.value.length) projectId.value = null
    await loadManuscripts()
  } finally {
    loaded.value = true
  }
  await refreshMe()
}

async function loadManuscripts() {
  if (!projectId.value) {
    manuscripts.value = []
    msTotal.value = 0
    return
  }
  const data = asPage(await api('/manuscripts' + qs({
    q: msQ.value,
    page: msPage.value,
    size: msSize.value,
    projectId: projectId.value
  })))
  manuscripts.value = data.items
  msTotal.value = data.total
  msPage.value = data.page
  msSize.value = data.size
}

function searchManuscripts() {
  msPage.value = 1
  loadManuscripts()
}

function selectProject(id) {
  if (projectId.value === id) return
  projectId.value = id
  msPage.value = 1
  loadManuscripts()
}

async function createProject() {
  const name = projectName.value.trim()
  if (!name) {
    toast('先写一个课题名称', 'err')
    return
  }
  const created = await api('/projects', { method: 'POST', body: { name } })
  toast('已新建课题「' + name + '」')
  projectName.value = ''
  projects.value = await api('/projects')
  projectId.value = created?.id ?? projects.value[0]?.id ?? null
  msPage.value = 1
  await loadManuscripts()
  await refreshMe()
}

function pick(fmt) {
  activeFmt.value = fmt.id
  picker.value?.click()
}

function openPicker() {
  pick(formats.find((f) => f.id === activeFmt.value) || formats[0])
}

function onFile(e) {
  takeFile(e.target.files?.[0])
  e.target.value = ''
}

function onDrop(e) {
  dragging.value = false
  takeFile(e.dataTransfer.files?.[0])
}

function takeFile(next) {
  error.value = ''
  if (!next) return
  const lower = next.name.toLowerCase()
  const ok = ['.pdf', '.docx', '.md', '.markdown', '.txt', '.tex'].some((ext) => lower.endsWith(ext))
  if (!ok) {
    error.value = '目前支持 PDF、Word、Markdown、TXT 和 TeX'
    toast(error.value, 'err')
    return
  }
  file.value = next
  if (lower.endsWith('.pdf')) activeFmt.value = 'pdf'
  else if (lower.endsWith('.docx')) activeFmt.value = 'docx'
  else if (lower.endsWith('.md') || lower.endsWith('.markdown')) activeFmt.value = 'md'
  else activeFmt.value = 'txt'
}

function clearFile() {
  file.value = null
}

function kind(name) {
  const lower = (name || '').toLowerCase()
  if (lower.endsWith('.pdf')) return 'PDF'
  if (lower.endsWith('.docx')) return 'Word'
  if (lower.endsWith('.md') || lower.endsWith('.markdown')) return 'MD'
  if (lower.endsWith('.tex')) return 'TeX'
  if (lower.endsWith('.txt')) return 'TXT'
  return '文件'
}

async function upload() {
  error.value = ''
  if (!projectId.value) {
    error.value = '请先新建课题'
    toast(error.value, 'err')
    return
  }
  if (!file.value) {
    error.value = '请先选择文件'
    return
  }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('projectId', projectId.value)
    fd.append('file', file.value)
    const ms = await api('/manuscripts', { method: 'POST', body: fd })
    toast('「' + file.value.name + '」已上传到「' + (currentProject.value?.name || '课题') + '」')
    file.value = null
    await loadManuscripts()
    if (ms?.id) router.push('/manuscripts/' + ms.id)
  } catch (e) {
    error.value = e.message
    toast('上传没有成功：' + e.message, 'err')
  } finally {
    uploading.value = false
  }
}
</script>
