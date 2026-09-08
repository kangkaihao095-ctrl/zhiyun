<template>
  <div class="task-page">
    <p class="page-kicker">审校结果</p>
    <h1 class="page-title">{{ title }}</h1>
    <p class="page-lead">
      <span class="status-pill" :data-s="task.status">{{ statusLabel(task.status) }}</span>
      {{ workflowName }}<template v-if="task.targetVenue"> · 投稿 {{ task.targetVenue }}</template> · {{ formatTime(task.createdAt) }}
    </p>
    <p v-if="task.id" class="page-ids">
      <CopyId label="任务 ID" :value="task.id" />
    </p>
    <p class="err" v-if="safeError">{{ safeError }}</p>
    <div class="row tr-export" v-if="task.id">
      <button class="btn btn-ghost" type="button" :disabled="exporting" @click="exportArtifactsJson">
        {{ exportingKind === 'json' ? '正在导出…' : '导出 Artifact' }}
      </button>
      <button class="btn btn-ghost" type="button" :disabled="exporting" @click="exportReport">
        {{ exportingKind === 'md' ? '正在导出…' : '导出汇总' }}
      </button>
      <span class="revise-hint">JSON 为当前任务已落库的 Artifact；汇总是同一批内容的 Markdown。不另建报告服务。</span>
    </div>
    <details v-if="artifacts.length" class="tr-art-list">
      <summary>按条下载 JSON（{{ artifacts.length }}）</summary>
      <button
        v-for="(a, i) in artifacts"
        :key="a.id || a.agent + a.artifactType + i"
        class="tr-art-item"
        type="button"
        :disabled="exporting"
        @click="exportOneArtifact(a)"
      >{{ artifactLabel(a) }}</button>
    </details>
    <p class="err" v-if="exportError">{{ exportError }}</p>
    <div class="tr-pipe" v-if="showPipe">
      <p class="tr-pipe-h">{{ workflowName }} · Agent Trace</p>
      <p v-if="task.id || fenceText" class="tr-pipe-fence">
        <CopyId v-if="task.id" label="任务 ID" :value="task.id" />
        <span v-if="fenceText">{{ fenceText }}</span>
      </p>
      <ol class="tr-pipe-list">
        <li
          v-for="node in pipeNodes"
          :key="node.id"
          class="tr-pipe-node"
          :data-st="node.state"
        >
          <span class="tr-pipe-st">{{ node.label }}</span>
          <span class="tr-pipe-name">{{ node.name }}</span>
          <span v-if="node.meta" class="tr-pipe-meta">{{ node.meta }}</span>
          <p v-if="node.errorMessage" class="tr-pipe-err">{{ node.errorMessage }}</p>
          <details v-if="node.errorTech" class="tr-tech">
            <summary>技术详情</summary>
            <p>{{ node.errorTech }}</p>
          </details>
        </li>
      </ol>
    </div>
    <div class="tr-retry" v-if="task.status === 'PENDING' || task.status === 'RUNNING'">
      <button class="btn btn-ghost" type="button" :disabled="cancelling" @click="cancelReview">
        {{ cancelling ? '正在取消…' : '取消这次审校' }}
      </button>
      <span class="revise-hint">取消后记为没能完成，可再从检查点继续。</span>
    </div>
    <p class="err" v-if="cancelError">{{ cancelError }}</p>
    <div class="tr-retry" v-if="task.status === 'FAILED'">
      <button class="btn btn-accent" type="button" :disabled="retrying" @click="retryFromCheckpoint">
        {{ retrying ? '正在从检查点继续…' : '从检查点继续' }}
      </button>
      <span class="revise-hint">从最近已完成的节点接着跑，不另扣额度。</span>
    </div>
    <p class="err" v-if="retryError">{{ retryError }}</p>
    <div class="tr-accept" v-if="task.status === 'WAITING_ACCEPT'">
      <p class="tr-accept-count" v-if="hasEdits">共 {{ displayPatches.length }} 处建议修改</p>
      <div class="row">
        <button class="btn btn-accent" type="button" :disabled="acting" @click="act('accept')">全部接受</button>
        <button class="btn btn-ghost" type="button" v-if="hasEdits" :disabled="acting" @click="beginPartial">部分接受</button>
        <button class="btn btn-ghost" type="button" :disabled="acting" @click="act('reject')">不采纳</button>
      </div>
      <div v-if="partialOpen" class="tr-partial">
        <div class="tr-partial-bar">
          <button type="button" @click="selectAllPatches">全选</button>
          <button type="button" @click="invertPatches">反选</button>
          <span class="muted">已选 {{ checkedKeys.length }} / 共 {{ displayPatches.length }}</span>
        </div>
        <label v-for="p in displayPatches" :key="p.key" class="tr-check">
          <input type="checkbox" :checked="checkedKeys.includes(p.key)" @change="togglePatchKey(p.key)">
          <span>
            <span class="tr-check-cat">{{ patchCatLabel(p) }}</span>
            <p class="tr-check-line">原文：{{ clip(p.original, 56) }}</p>
            <p class="tr-check-line">建议：{{ clip(p.proposed, 56) }}</p>
          </span>
        </label>
        <div class="row tr-partial-actions">
          <button class="btn btn-accent" type="button" :disabled="acting || !checkedKeys.length" @click="confirmPartial">确认部分接受</button>
          <button class="btn btn-ghost" type="button" :disabled="acting" @click="partialOpen = false">取消</button>
        </div>
      </div>
      <p class="err" v-if="acceptError">{{ acceptError }}</p>
    </div>
    <div class="revise-bar" v-if="canContinueEdit">
      <button class="btn btn-accent" type="button" :disabled="startingFull" @click="continueFullReview">
        {{ startingFull ? '正在开始改稿…' : '按这些问题改稿' }}
      </button>
      <span class="revise-hint">{{ estimateHint }}</span>
    </div>
    <p class="err" v-if="continueError">{{ continueError }}</p>

    <div class="callout" v-if="ready">
      <template v-if="task.status === 'WAITING_ACCEPT' && hasEdits">
        系统给出 {{ displayPatches.length }} 处建议修改（候选稿，不会直接覆盖正式稿）。先点左边的问题或右边某一类，再点「现在」的原句，正文会跳过去并弹出建议。可以全部接受、部分勾选接受，或不采纳。
      </template>
      <template v-else-if="hasEdits">
        系统给出 {{ displayPatches.length }} 处建议修改（候选稿，不会直接覆盖正式稿）。先点某一类，再点「现在」的原句，可对照建议。
      </template>
      <template v-else-if="canContinueEdit">
        这次是{{ workflowName }}，只标问题、不改正文，所以还没有「修改稿」。点左边一条，右边会跳到原文对应位置。若觉得这些问题没问题，可点「按这些问题改稿」：会另开一次完整审校，产出候选稿，需你接受后才替换正文。
      </template>
      <template v-else-if="task.status === 'WAITING_ACCEPT'">
        这次没有句子级的建议修改。点左边一条，右边会跳到原文对应位置。可以全部接受或不采纳。
      </template>
      <template v-else>
        这次是{{ workflowName }}，只标问题、不改正文，所以没有「修改稿」。点左边一条，右边会跳到原文对应位置。若要系统直接改句子，请再开一次「完整审校」。
      </template>
    </div>

    <MergePreview
      v-if="showMerge && merge"
      :official="merge.official"
      :preview="merge.preview"
      :score="merge.score"
      :grade="merge.grade"
      :points="merge.points || []"
      :reasons="merge.reasons || []"
      :mode="merge.mode"
    />

    <div class="panel" v-if="inspect.pagePreview">
      <PdfInspectPanel :inspect="inspect" @open="openSourceFile" />
    </div>

    <div class="tr-kinds" v-if="kindGroups.length">
      <h3>改稿计划</h3>
      <p class="muted tr-hint">按计划分成需要你处理、人机协作、可由系统改三类。</p>
      <div v-for="g in kindGroups" :key="g.key" class="tr-group" :data-kind="g.key">
        <button
          class="tr-group-h"
          :class="{ on: openKind === g.key }"
          type="button"
          @click="toggleKind(g.key)"
        >
          <span>{{ g.label }}</span>
          <span class="muted">{{ g.items.length }} 处</span>
        </button>
        <div v-if="openKind === g.key" class="tr-group-body">
          <button
            v-for="item in g.items"
            :key="item.id || item.issueId + item.instruction"
            class="finding-card"
            :class="{ on: item.issueId && item.issueId === selectedId }"
            type="button"
            @click="selectRevisionTask(item)"
          >
            <p class="finding-sum">{{ item.instruction }}</p>
            <p v-if="item.issueId" class="finding-action">关联问题 {{ item.issueId }}</p>
            <PriorityWhy
              v-if="item.high"
              :why-high="item.whyHigh"
              :suggest-fix="item.suggestFix"
              :evidence="item.evidenceView"
            />
          </button>
        </div>
      </div>
    </div>

    <div class="grid-2 task-grid">
      <div class="panel">
        <h3>需要你看的</h3>
        <div v-if="!ready && !issues.length" class="muted">还在整理，稍等片刻。</div>
        <div v-else-if="!issues.length" class="muted">没有需要你特别留意的条目。</div>
        <template v-else>
          <button class="tr-summary" type="button" @click="findingsOpen = !findingsOpen">
            <span>{{ findingSummary }}</span>
            <span class="muted">{{ findingsOpen ? '收起' : '展开' }}</span>
          </button>
          <p class="muted tr-hint" v-if="findingsOpen">点一条，右侧正文会跳到对应位置并高亮。</p>
          <div class="tr-filters" v-if="findingsOpen && sevPills.length">
            <button
              v-for="pill in sevPills"
              :key="pill.key"
              class="tr-pill"
              :class="{ on: sevFilter === pill.key }"
              type="button"
              @click="setSevFilter(pill.key)"
            >{{ pill.label }} {{ pill.count }}</button>
          </div>
          <div v-if="findingsOpen" class="tr-scroll">
            <div v-for="g in issueGroups" :key="g.key" class="tr-group">
              <button
                class="tr-group-h"
                :class="{ on: openFindingGroup === g.key }"
                type="button"
                @click="toggleFindingGroup(g.key)"
              >
                <span>{{ g.label }}</span>
                <span class="muted">{{ g.items.length }} 处</span>
              </button>
              <div v-if="openFindingGroup === g.key" class="tr-group-body">
                <button
                  v-for="item in g.items"
                  :key="item.id"
                  class="finding-card"
                  :class="{ on: item.id === selectedId }"
                  type="button"
                  @click="selectFinding(item)"
                >
                  <div class="finding-top">
                    <span class="sev" :data-s="item.severity">{{ severityLabel(item.severity) }}</span>
                    <span class="finding-agent">{{ item.agent }}</span>
                  </div>
                  <p class="finding-sum">{{ item.summary }}</p>
                  <p v-if="item.quote" class="finding-quote">原文里的位置：{{ item.quote }}</p>
                  <PriorityWhy
                    v-if="item.high"
                    :why-high="item.whyHigh"
                    :suggest-fix="item.suggestFix"
                    :evidence="item.evidenceView"
                  />
                  <p v-else class="finding-action">{{ item.action }}</p>
                </button>
              </div>
            </div>
          </div>
        </template>
      </div>

      <div class="panel">
        <h3>{{ hasEdits ? '建议修改' : '原文里的位置' }}</h3>

        <template v-if="hasEdits">
          <p class="muted">按来源收起。点开一类后再点「现在」的原句，正文会跳过去并弹出建议。</p>
          <div v-for="g in patchGroups" :key="g.key" class="patch-cat">
            <button
              class="patch-cat-h"
              :class="{ on: openCat === g.key }"
              type="button"
              @click="toggleCat(g.key)"
            >
              <span>{{ g.label }}</span>
              <span class="muted">{{ g.items.length }} 处</span>
            </button>
            <div v-if="openCat === g.key" class="patch-cat-body">
              <button
                v-for="p in g.items"
                :key="p.key"
                class="patch-now"
                :class="{ on: p.key === activePatchKey }"
                type="button"
                @click="openPatch(p)"
              >
                <span class="patch-now-k">现在</span>
                <span class="patch-now-t">{{ clip(p.original) }}</span>
                <PriorityWhy
                  v-if="p.high"
                  :why-high="p.whyHigh"
                  :suggest-fix="p.suggestFix"
                  :evidence="p.evidenceView"
                />
              </button>
            </div>
          </div>
        </template>

        <p class="muted" style="margin-top:12px">
          {{ hasEdits ? '全文（黄底是当前问题；带虚线的句子可点，查看建议）' : '标黄的就是当前选中的问题' }}
        </p>
        <p v-if="missHint" class="locate-miss">{{ missHint }}</p>
        <div ref="manuscriptEl" class="ms-body" @scroll="onMsScroll">
          <template v-for="(part, i) in bodyParts" :key="i">
            <mark
              v-if="part.hit || part.patchKey"
              :class="{ hit: part.hit, 'patch-span': Boolean(part.patchKey), 'patch-on': part.patchOn }"
              :data-pk="part.patchKey || undefined"
              :tabindex="part.patchKey ? 0 : undefined"
              @click="part.patchKey && openPatchByKey(part.patchKey)"
              @keydown.enter.prevent="part.patchKey && openPatchByKey(part.patchKey)"
            >{{ part.text }}</mark>
            <span v-else>{{ part.text }}</span>
          </template>
          <span v-if="!officialText" class="muted">暂无正文</span>
        </div>
      </div>
    </div>
    <p class="muted" style="margin-top:8px">
      <router-link class="text-link" to="/history">返回审校记录</router-link>
      ·
      <router-link v-if="task.manuscriptId" class="text-link" :to="'/manuscripts/' + task.manuscriptId">打开论文</router-link>
    </p>

    <PatchPopover
      v-if="pop"
      :proposed="pop.proposed"
      :reason="pop.reason"
      :top="popPos.top"
      :left="popPos.left"
      :sheet="popSheet"
      @close="closePop"
    />
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, downloadJson, downloadMarkdown, fetchAuthBlob } from '../api'
import { formatTime, statusLabel } from '../labels'
import {
  AGENT_NAME,
  artifactDownloadName,
  buildReportMarkdown,
  CAT_META,
  SEV_META,
  catKeyOfIssue,
  catKeyOfPatch,
  clip,
  collect,
  expandRange,
  findAllIndexes,
  findRelatedIssue,
  isDoiToken,
  locateNeedle,
  meaningfulPatch,
  paintRanges,
  paragraphHunks,
  pickFindingNeedle,
  fenceLabel,
  mergeTraceNodes,
  pipelineNodes,
  revisionKindGroups,
  showAgentTrace,
  sevKey,
  severityLabel,
  spotsNeedle,
  toExportArtifacts,
  whyOf
} from '../review'
import { publicErrorMessage } from '../public-error'
import { toast } from '../toast'
import PatchPopover from '../components/PatchPopover.vue'
import MergePreview from '../components/MergePreview.vue'
import PdfInspectPanel from '../components/PdfInspectPanel.vue'
import CopyId from '../components/CopyId.vue'
import PriorityWhy from '../components/PriorityWhy.vue'

const route = useRoute()
const router = useRouter()
const openRecharge = inject('openRecharge', () => router.push('/billing'))
const refreshMe = inject('refreshMe', () => Promise.resolve())
const task = ref({})
const artifacts = ref([])
const diff = ref({})
const catalogs = ref([])
const billing = ref({})
const me = ref({})
const selectedId = ref('')
const manuscriptEl = ref(null)
const startingFull = ref(false)
const continueError = ref('')
const retryError = ref('')
const retrying = ref(false)
const cancelError = ref('')
const cancelling = ref(false)
const acceptError = ref('')
const acting = ref(false)
const findingsOpen = ref(false)
const sevFilter = ref('')
const openFindingGroup = ref('')
const openKind = ref('')
const partialOpen = ref(false)
const checkedKeys = ref([])
const openCat = ref('')
const activePatchKey = ref('')
const pop = ref(null)
const popPos = ref({ top: 0, left: 0 })
const merge = ref(null)
const inspect = ref({})
const trace = ref({ nodes: [] })
const exporting = ref(false)
const exportingKind = ref('')
const exportError = ref('')
const viewportW = ref(typeof window !== 'undefined' ? window.innerWidth : 1200)
let timer
let quietSelect = false
let inboxMeSynced = ''

const workflowName = computed(() => {
  const hit = catalogs.value.find((w) => w.id === task.value.workflow)
  return hit?.name || '审校'
})
const title = computed(() => workflowName.value + (task.value.id ? ' · 第 ' + task.value.id + ' 次' : ''))
const ready = computed(() => ['DONE', 'WAITING_ACCEPT', 'FAILED'].includes(task.value.status))
const showMerge = computed(() => task.value.status === 'WAITING_ACCEPT')
const officialText = computed(() => diff.value.official || '')
const pipeNodes = computed(() => mergeTraceNodes(pipelineNodes(
  task.value.workflow,
  task.value.checkpointAgent,
  task.value.status,
  task.value.errorMessage
), trace.value))
const showPipe = computed(() => showAgentTrace(task.value.status) && pipeNodes.value.length > 0)
const fenceText = computed(() => fenceLabel(task.value, trace.value))
const safeError = computed(() => publicErrorMessage(task.value.errorMessage))

const parsed = computed(() => collect(artifacts.value))
const issues = computed(() => parsed.value.issues)
const patches = computed(() => parsed.value.patches)
const tasks = computed(() => parsed.value.tasks)
const selected = computed(() => issues.value.find((x) => x.id === selectedId.value) || issues.value[0] || null)

const hunks = computed(() => paragraphHunks(diff.value.official || '', diff.value.candidate || ''))

const displayPatches = computed(() => {
  const fromPatches = patches.value.filter(meaningfulPatch)
  if (fromPatches.length) return fromPatches
  return hunks.value.filter(meaningfulPatch)
})

const hasEdits = computed(() => displayPatches.value.length > 0)

const canContinueEdit = computed(() => {
  const wf = task.value.workflow
  return task.value.status === 'DONE' && (wf === 'CITATION_ONLY' || wf === 'QUICK_REVIEW')
})

const fullCap = computed(() => {
  const hit = catalogs.value.find((w) => w.id === 'FULL_REVIEW')
  return Number(hit?.capPoints) || 10
})

const estimateHint = computed(() => {
  const cap = fullCap.value
  const per = Number(billing.value.tokensPerPoint) || 2000
  return `预计消耗 1–${cap} 额度（上限 ${cap}，约每 ${per} token 计 1 额度，按实际结算）`
})

const popSheet = computed(() => viewportW.value <= 960)

const findingLocate = computed(() => pickFindingNeedle(selected.value, officialText.value))

const missHint = computed(() => {
  if (!officialText.value) return ''
  if (activePatchKey.value) {
    const p = displayPatches.value.find((x) => x.key === activePatchKey.value)
    if (p && locateNeedle(officialText.value, p.original).length === 0) {
      return '正文里没找到这段，请看左侧摘要'
    }
    return ''
  }
  if (selected.value && !findingLocate.value) return '正文里没找到这段，请看左侧摘要'
  return ''
})

const issueGroups = computed(() => {
  const groups = new Map(SEV_META.map((m) => [m.key, { ...m, items: [] }]))
  for (const item of issues.value) {
    const key = sevKey(item.severity)
    const g = groups.get(key) || groups.get('OTHER')
    g.items.push(item)
  }
  return SEV_META.map((m) => groups.get(m.key))
    .filter((g) => g.items.length)
    .filter((g) => !sevFilter.value || g.key === sevFilter.value)
})

const sevPills = computed(() => {
  const counts = {}
  for (const item of issues.value) {
    const key = sevKey(item.severity)
    counts[key] = (counts[key] || 0) + 1
  }
  const pills = [{ key: '', label: '全部', count: issues.value.length }]
  for (const m of SEV_META) {
    if (counts[m.key]) pills.push({ key: m.key, label: m.label, count: counts[m.key] })
  }
  return pills
})

const findingSummary = computed(() => {
  const n = issues.value.length
  const counts = {}
  for (const item of issues.value) {
    const key = sevKey(item.severity)
    counts[key] = (counts[key] || 0) + 1
  }
  const parts = [`需要你看 ${n} 处`]
  if (counts.HIGH) parts.push(`高 ${counts.HIGH}`)
  if (counts.CRITICAL) parts.push(`必须处理 ${counts.CRITICAL}`)
  if (counts.MEDIUM) parts.push(`中 ${counts.MEDIUM}`)
  if (counts.NOT_VERIFIED) parts.push(`未能核实 ${counts.NOT_VERIFIED}`)
  return parts.join(' · ')
})

const patchGroups = computed(() => {
  const groups = new Map(CAT_META.map((m) => [m.key, { ...m, items: [] }]))
  for (const p of displayPatches.value) {
    const key = catKeyOfPatch(p, issues.value)
    const g = groups.get(key) || groups.get('OTHER')
    g.items.push(p)
  }
  return CAT_META.map((m) => groups.get(m.key)).filter((g) => g.items.length)
})

const kindGroups = computed(() => revisionKindGroups(tasks.value))

const bodyParts = computed(() => {
  const text = officialText.value
  if (!text) return []
  const ranges = []
  const found = findingLocate.value
  if (found) {
    const hits = found.all ? findAllIndexes(text, found.needle) : findAllIndexes(text, found.needle).slice(0, 1)
    for (const j of hits) {
      let start = j
      let end = j + found.needle.length
      if (found.expand) {
        const exp = expandRange(text, start, end)
        start = exp.start
        end = exp.end
      }
      ranges.push({ start, end, kind: 'finding' })
    }
  }
  for (const p of displayPatches.value) {
    const orig = String(p.original || '')
    const spots = locateNeedle(text, orig)
    if (!spots.length) continue
    const useAll = isDoiToken(orig)
    const picked = useAll ? spots : spots.slice(0, 1)
    for (const j of picked) {
      const needle = spotsNeedle(text, orig, j)
      let start = j
      let end = j + needle.length
      if (needle.length < 48) {
        const exp = expandRange(text, start, end)
        start = exp.start
        end = exp.end
      }
      ranges.push({ start, end, kind: 'patch', key: p.key })
    }
  }
  return paintRanges(text, ranges, activePatchKey.value)
})

watch(issues, (list) => {
  if (!list.length) {
    selectedId.value = ''
    return
  }
  if (!list.some((x) => x.id === selectedId.value)) {
    selectedId.value = list[0].id
  }
})

watch(issueGroups, (groups) => {
  if (!groups.length) {
    openFindingGroup.value = ''
    return
  }
  if (!groups.some((g) => g.key === openFindingGroup.value)) {
    openFindingGroup.value = groups[0].key
  }
})

watch(kindGroups, (groups) => {
  if (!groups.length) {
    openKind.value = ''
    return
  }
  if (!groups.some((g) => g.key === openKind.value)) {
    openKind.value = groups[0].key
  }
})

onMounted(async () => {
  try {
    const data = await api('/workflows')
    catalogs.value = data.workflows || []
    billing.value = data.billing || {}
  } catch {
    catalogs.value = []
  }
  try {
    me.value = await api('/me')
  } catch {
    me.value = {}
  }
  await refresh()
  timer = setInterval(refresh, 1500)
  window.addEventListener('keydown', onKey)
  window.addEventListener('resize', onWin)
  window.addEventListener('scroll', onWin, true)
  document.addEventListener('click', onDocClick)
})
onUnmounted(() => {
  clearInterval(timer)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', onWin)
  window.removeEventListener('scroll', onWin, true)
  document.removeEventListener('click', onDocClick)
})

async function refresh() {
  task.value = await api('/reviews/' + route.params.id)
  artifacts.value = await api('/reviews/' + route.params.id + '/artifacts')
  try {
    trace.value = await api('/reviews/' + route.params.id + '/trace')
  } catch {
    trace.value = { nodes: [] }
  }
  try {
    const key = String(route.params.id)
    await api('/inbox/read', { method: 'POST', body: { refId: 'task-' + key } })
    const terminal = ['DONE', 'WAITING_ACCEPT', 'FAILED'].includes(task.value.status)
    if (inboxMeSynced !== key || terminal) {
      inboxMeSynced = key
      await refreshMe()
    }
  } catch {
    /* 角标刷新失败不挡结果页 */
  }
  try {
    diff.value = await api('/reviews/' + route.params.id + '/diff')
  } catch {
    diff.value = {}
  }
  if (['DONE', 'WAITING_ACCEPT', 'FAILED'].includes(task.value.status)) {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    try {
      me.value = await api('/me')
    } catch {
      /* keep last known quota */
    }
  } else if (!timer) {
    timer = setInterval(refresh, 1500)
  }
  await loadMergePreview()
  await loadInspect()
}

watch(selectedId, (id, prev) => {
  if (id && id !== prev) {
    const item = issues.value.find((x) => x.id === id)
    const k = catKeyOfIssue(item)
    if (patchGroups.value.some((g) => g.key === k)) openCat.value = k
  }
  if (quietSelect) return
  scrollToHit(true)
})
watch(officialText, () => scrollToHit(false))
watch([partialOpen, checkedKeys], () => {
  loadMergePreview()
})

async function loadMergePreview() {
  if (!showMerge.value) {
    merge.value = null
    return
  }
  try {
    if (partialOpen.value) {
      merge.value = await api('/reviews/' + route.params.id + '/merge-preview', {
        method: 'POST',
        body: { patchIds: checkedKeys.value }
      })
    } else {
      merge.value = await api('/reviews/' + route.params.id + '/merge-preview')
    }
  } catch {
    merge.value = null
  }
}

async function loadInspect() {
  const msId = task.value.manuscriptId
  const ver = task.value.sourceVersion
  if (!msId || !ver) {
    inspect.value = {}
    return
  }
  if (inspect.value.manuscriptId === msId && inspect.value.versionNo === ver) {
    return
  }
  try {
    inspect.value = await api('/manuscripts/' + msId + '/versions/' + ver + '/inspect')
  } catch {
    inspect.value = {}
  }
}

async function openSourceFile() {
  const msId = task.value.manuscriptId
  const ver = task.value.sourceVersion
  if (!msId || !ver) return
  try {
    const blob = await fetchAuthBlob('/manuscripts/' + msId + '/versions/' + ver + '/file')
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank', 'noopener')
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (e) {
    toast(e.message || '打不开原件', 'err')
  }
}

function taskExportId() {
  return task.value.id || route.params.id
}

function artifactLabel(a) {
  const agent = AGENT_NAME[a.agent] || a.agent || 'Agent'
  return `${agent} · ${a.artifactType || 'Artifact'}`
}

async function loadReportMarkdown() {
  const id = taskExportId()
  try {
    const data = await api('/reviews/' + id + '/report')
    if (data && typeof data.markdown === 'string' && data.markdown.trim()) {
      return { filename: data.filename, markdown: data.markdown, artifacts: data.artifacts }
    }
  } catch {
    /* 后端未重启或 /report 不可用时，用页上已有 Artifact 拼 */
  }
  return {
    filename: 'zhiyun-review-' + id + '.md',
    markdown: buildReportMarkdown(task.value, artifacts.value, { workflowName: workflowName.value }),
    artifacts: artifacts.value
  }
}

function beginExport(kind) {
  exportError.value = ''
  exporting.value = true
  exportingKind.value = kind
}

function endExport() {
  exporting.value = false
  exportingKind.value = ''
}

function failExport(e, fallback) {
  const msg = e.message || fallback
  exportError.value = msg
  toast(msg, 'err')
}

async function exportArtifactsJson() {
  beginExport('json')
  try {
    const id = taskExportId()
    if (!id) throw new Error('还没有任务，无法导出')
    const report = await loadReportMarkdown()
    const rows = toExportArtifacts(report.artifacts && report.artifacts.length ? report.artifacts : artifacts.value)
    downloadJson('zhiyun-artifacts-' + id + '.json', {
      taskId: Number(id) || id,
      workflow: task.value.workflow || '',
      status: task.value.status || '',
      markdown: report.markdown || '',
      artifacts: rows
    })
    toast(rows.length ? '已下载 Artifact' : '已下载。这次还没有 Artifact。')
  } catch (e) {
    failExport(e, '没能导出 Artifact')
  } finally {
    endExport()
  }
}

async function exportReport() {
  beginExport('md')
  try {
    const id = taskExportId()
    if (!id) throw new Error('还没有任务，无法导出')
    const report = await loadReportMarkdown()
    downloadMarkdown(report.filename || ('zhiyun-review-' + id + '.md'), report.markdown)
    toast('已下载结果汇总')
  } catch (e) {
    failExport(e, '没能导出汇总')
  } finally {
    endExport()
  }
}

function exportOneArtifact(a) {
  exportError.value = ''
  try {
    const id = taskExportId()
    if (!id) throw new Error('还没有任务，无法导出')
    const rows = toExportArtifacts([a])
    const row = rows[0] || { payload: a?.payload || '' }
    downloadJson(artifactDownloadName(id, a), row)
    toast('已下载 ' + artifactLabel(a))
  } catch (e) {
    failExport(e, '没能导出这条 Artifact')
  }
}

async function selectFinding(item) {
  closePop()
  findingsOpen.value = true
  openFindingGroup.value = sevKey(item.severity)
  selectedId.value = item.id
  const k = catKeyOfIssue(item)
  if (patchGroups.value.some((g) => g.key === k)) openCat.value = k
}

function toggleFindingGroup(key) {
  openFindingGroup.value = openFindingGroup.value === key ? '' : key
}

function toggleKind(key) {
  openKind.value = openKind.value === key ? '' : key
}

function selectRevisionTask(item) {
  const issue = issues.value.find((x) => x.id === item.issueId)
  if (issue) {
    selectFinding(issue)
    return
  }
  findingsOpen.value = true
}

async function cancelReview() {
  cancelError.value = ''
  cancelling.value = true
  try {
    const next = await api('/reviews/' + route.params.id + '/cancel', { method: 'POST' })
    task.value = next
    toast('已取消这次审校')
    await refresh()
  } catch (e) {
    const msg = e.message || '没能取消'
    cancelError.value = msg
    toast(msg, 'err')
  } finally {
    cancelling.value = false
  }
}

async function retryFromCheckpoint() {
  retryError.value = ''
  retrying.value = true
  try {
    const next = await api('/reviews/' + route.params.id + '/retry', { method: 'POST' })
    task.value = next
    toast('已从检查点继续。不另扣额度。')
    if (!timer && ['PENDING', 'RUNNING'].includes(next.status)) {
      timer = setInterval(refresh, 1500)
    }
    await refresh()
  } catch (e) {
    const msg = e.message || '没能从检查点继续'
    retryError.value = msg
    toast(msg, 'err')
  } finally {
    retrying.value = false
  }
}

function setSevFilter(key) {
  sevFilter.value = sevFilter.value === key ? '' : key
}

function patchCatLabel(p) {
  const key = catKeyOfPatch(p, issues.value)
  return CAT_META.find((m) => m.key === key)?.label || '其他修改'
}

function beginPartial() {
  partialOpen.value = true
  checkedKeys.value = displayPatches.value.map((p) => p.key)
  acceptError.value = ''
}

function selectAllPatches() {
  checkedKeys.value = displayPatches.value.map((p) => p.key)
}

function invertPatches() {
  const set = new Set(checkedKeys.value)
  checkedKeys.value = displayPatches.value.map((p) => p.key).filter((k) => !set.has(k))
}

function togglePatchKey(key) {
  if (checkedKeys.value.includes(key)) {
    checkedKeys.value = checkedKeys.value.filter((k) => k !== key)
  } else {
    checkedKeys.value = [...checkedKeys.value, key]
  }
}

function toggleCat(key) {
  openCat.value = openCat.value === key ? '' : key
}

async function openPatchByKey(key) {
  const p = displayPatches.value.find((x) => x.key === key)
  if (p) await openPatch(p)
}

async function openPatch(p) {
  const issue = findRelatedIssue(p, issues.value)
  if (issue && issue.id !== selectedId.value) {
    quietSelect = true
    selectedId.value = issue.id
    findingsOpen.value = true
    openFindingGroup.value = sevKey(issue.severity)
    quietSelect = false
  }
  activePatchKey.value = p.key
  openCat.value = catKeyOfPatch(p, issues.value)
  pop.value = {
    proposed: p.proposed || '',
    reason: whyOf(p, issues.value, tasks.value)
  }
  await nextTick()
  await scrollToSelector('mark.patch-on', true)
  placePopover()
  await nextTick()
  placePopover()
}

function closePop() {
  activePatchKey.value = ''
  pop.value = null
}

function onKey(e) {
  if (e.key === 'Escape' && pop.value) closePop()
}

function onWin() {
  viewportW.value = window.innerWidth
  if (pop.value) placePopover()
}

function onMsScroll() {
  if (pop.value) placePopover()
}

function onDocClick(e) {
  if (!pop.value) return
  const t = e.target
  if (!(t instanceof Element)) return
  if (t.closest('.patch-pop, .patch-now, mark.patch-span')) return
  closePop()
}

function placePopover() {
  if (popSheet.value) return
  const box = manuscriptEl.value
  const mark = box?.querySelector('mark.patch-on')
  const panel = document.querySelector('.patch-pop')
  const vw = window.innerWidth
  const vh = window.innerHeight
  const popW = panel?.offsetWidth || Math.min(360, vw - 24)
  const popH = panel?.offsetHeight || 220
  if (!mark) {
    const r = box?.getBoundingClientRect()
    popPos.value = {
      top: Math.max(8, (r?.top || 80) + 16),
      left: Math.max(8, (r?.left || 24) + 16)
    }
    return
  }
  const r = mark.getBoundingClientRect()
  let left = Math.min(Math.max(8, r.left), vw - popW - 8)
  let top = r.bottom + 8
  if (top + popH > vh - 8) top = Math.max(8, r.top - popH - 8)
  if (top + popH > vh - 8) top = Math.max(8, vh - popH - 8)
  popPos.value = { top, left }
}

async function scrollToHit(smooth) {
  await nextTick()
  if (activePatchKey.value) {
    await scrollToSelector('mark.patch-on', smooth)
    return
  }
  await scrollToSelector('mark.hit', smooth)
}

async function scrollToSelector(sel, smooth) {
  await nextTick()
  const box = manuscriptEl.value
  const mark = box?.querySelector(sel)
  if (!box || !mark) return
  const top = mark.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop - 88
  box.scrollTo({ top: Math.max(0, top), behavior: smooth ? 'smooth' : 'auto' })
}

async function continueFullReview() {
  continueError.value = ''
  const manuscriptId = task.value.manuscriptId
  if (!manuscriptId) {
    continueError.value = '找不到对应论文，无法继续改稿。'
    return
  }
  try {
    me.value = await api('/me')
  } catch {
    /* 用上次读到的余额 */
  }
  const balance = Number(me.value.quota ?? 0)
  if (balance < 1) {
    continueError.value = '额度不够了，请先去充值。'
    toast('额度不够了，请先去充值', 'err')
    openRecharge()
    return
  }
  startingFull.value = true
  try {
    const next = await api('/manuscripts/' + manuscriptId + '/reviews', {
      method: 'POST',
      body: { workflow: 'FULL_REVIEW', targetVenue: task.value.targetVenue || undefined }
    })
    toast('已开始完整审校。结束后给出候选稿，需你接受后才替换正文。')
    router.push('/reviews/' + next.id)
  } catch (e) {
    const msg = e.message || '没能开始改稿'
    continueError.value = msg
    toast(msg, 'err')
    if (String(msg).includes('额度')) {
      router.push('/billing')
    }
  } finally {
    startingFull.value = false
  }
}

async function act(kind) {
  acceptError.value = ''
  acting.value = true
  try {
    await api('/reviews/' + route.params.id + '/' + kind, { method: 'POST', body: {} })
    toast(kind === 'accept' ? '已接受全部修改，正式稿已更新。' : '已保持原文，未采纳这次修改。')
    partialOpen.value = false
    await refresh()
  } catch (e) {
    const msg = e.message || '操作没有成功'
    acceptError.value = msg
    toast(msg, 'err')
  } finally {
    acting.value = false
  }
}

async function confirmPartial() {
  acceptError.value = ''
  if (!checkedKeys.value.length) {
    acceptError.value = '请至少勾选一处修改'
    toast(acceptError.value, 'err')
    return
  }
  acting.value = true
  try {
    const res = await api('/reviews/' + route.params.id + '/accept-partial', {
      method: 'POST',
      body: { patchIds: checkedKeys.value }
    })
    const skipped = Array.isArray(res.skipped) ? res.skipped : []
    if (skipped.length) {
      toast(`已写入正式稿。${skipped.length} 处原文找不到，已跳过。`)
    } else {
      toast(`已部分接受 ${res.applied} 处，正式稿已更新。`)
    }
    partialOpen.value = false
    await refresh()
  } catch (e) {
    const msg = e.message || '部分接受没有成功'
    acceptError.value = msg
    toast(msg, 'err')
  } finally {
    acting.value = false
  }
}
</script>

<style scoped src="./task-review.css"></style>
