<template>
  <div class="home-page papers-page">
    <header class="page-head">
      <div>
        <p class="page-kicker">论文</p>
        <h1 class="page-title">你好{{ me.displayName ? '，' + me.displayName : '' }}</h1>
        <p class="page-lead">课题用来分组和筛选稿件。先选定课题，再上传；下面的列表只显示当前课题里的论文。系统不会直接改你的原文。</p>
      </div>
    </header>

    <div class="papers-stack">
      <section class="panel papers-section project-zone">
        <p class="panel-label">课题</p>
        <h3>{{ currentProject ? ('当前课题：' + currentProject.name) : '还没有课题' }}</h3>
        <p class="muted">选中的课题会高亮。上传和论文列表都跟着当前课题走。</p>
        <div v-if="projects.length" class="project-chips zy-select-chips" role="list">
          <button
            v-for="p in projects"
            :key="p.id"
            type="button"
            class="project-chip"
            :class="{ on: projectId === p.id }"
            :aria-pressed="projectId === p.id"
            data-testid="project-chip"
            @click="selectProject(p.id)"
          >{{ p.name }}</button>
        </div>
        <div v-else class="empty-block">先建一个课题，再上传论文。</div>
        <div class="project-create">
          <input
            class="field"
            v-model="projectName"
            maxlength="80"
            placeholder="课题名称，例如 ACL 投稿"
            aria-label="新课题名称"
          />
          <button class="btn btn-accent" type="button" @click="createProject">新建课题</button>
        </div>
        <p class="muted project-required">名称必填。</p>
      </section>

      <section class="panel papers-section upload-zone" :class="{ dim: !projectId }">
        <p class="panel-label">上传</p>
        <h3 v-if="currentProject">上传到「{{ currentProject.name }}」</h3>
        <h3 v-else>先建课题再上传</h3>
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
          <p class="drop-title">{{ file ? file.name : '拖入文件，或点这里选择' }}</p>
          <p class="muted">{{ file ? fileHint : 'PDF · Word · Markdown · TXT · TeX' }}</p>
        </div>
        <div class="row upload-actions">
          <button class="btn btn-accent" type="button" :disabled="!file || !projectId || uploading" @click="upload">
            {{ uploading ? '正在读取…' : '上传并打开' }}
          </button>
          <button v-if="file" class="btn btn-ghost" type="button" :disabled="uploading" @click="clearFile">换一份</button>
        </div>
        <p class="err" v-if="error">{{ error }}</p>
      </section>

      <section class="panel papers-section list-zone">
        <p class="panel-label">论文</p>
        <h3 v-if="currentProject">「{{ currentProject.name }}」里的论文</h3>
        <h3 v-else>我的论文</h3>
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
          <div class="ms-list">
            <router-link class="ms-card" v-for="m in manuscripts" :key="m.id" :to="'/manuscripts/' + m.id">
              <span class="ms-kind">{{ kind(m.title) }}</span>
              <strong>{{ m.title }}</strong>
              <span class="muted">第 {{ m.currentVersion }} 版 · 点开预览</span>
            </router-link>
          </div>
        </template>
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
  me.value = await api('/me')
  projects.value = await api('/projects')
  if (projects.value.length && !projects.value.some((p) => p.id === projectId.value)) {
    projectId.value = projects.value[0].id
  }
  if (!projects.value.length) projectId.value = null
  await loadManuscripts()
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
