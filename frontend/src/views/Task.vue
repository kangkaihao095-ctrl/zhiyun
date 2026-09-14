<template>
  <div class="task-page">
    <p class="page-kicker">审校结果</p>
    <h1 class="page-title">{{ title }}</h1>
    <p class="page-lead">
      <span class="status-pill" :data-s="task.status">{{ statusLabel(task.status) }}</span>
      {{ workflowName }}<template v-if="task.targetVenue"> · 投稿 {{ task.targetVenue }}</template> · {{ formatTime(task.createdAt) }}
    </p>
    <div class="tr-usage-sum" data-testid="task-usage-summary">
      <span>{{ usageCols.duration }}</span>
      <span>{{ usageCols.tokens }}</span>
      <span>{{ usageCols.quota }}</span>
      <em v-if="usageCols.inProgress">进行中</em>
    </div>

    <nav class="tr-tabs" role="tablist" aria-label="审校结果分类">
      <button
        v-for="p in TASK_PANELS"
        :key="p.key"
        type="button"
        role="tab"
        class="tr-tab"
        :class="{ on: panel === p.key }"
        :aria-selected="panel === p.key"
        :data-testid="'task-tab-' + p.key"
        @click="setPanel(p.key)"
      >
        {{ p.label }}
        <span class="tr-tab-n">{{ panelCounts[p.key] }}</span>
        <em v-if="p.key === 'confirm' && task.status === 'WAITING_ACCEPT'">待确认</em>
      </button>
    </nav>

    <section v-show="panel === 'overview'" data-testid="task-panel-overview">
      <p v-if="task.id" class="page-ids">
        <CopyId label="任务 ID" :value="task.id" />
      </p>
      <p class="err" v-if="safeError">{{ safeError }}</p>
      <div class="tr-usage" data-testid="task-usage">
        <p class="tr-pipe-h">本次用量</p>
        <div class="tr-usage-cols" data-testid="task-usage-cols">
          <div>
            <span>耗时</span>
            <strong>{{ usageCols.duration }}</strong>
          </div>
          <div>
            <span>token</span>
            <strong>{{ usageCols.tokens }}</strong>
          </div>
          <div>
            <span>额度</span>
            <strong>{{ usageCols.quota }}</strong>
          </div>
        </div>
        <p v-if="usageCols.inProgress" class="muted">进行中，尚未结算。</p>
        <p v-else-if="usageCols.settled" class="muted">已结算。</p>
        <p v-else-if="!usageRunning.length && !usageSettled.length" class="muted">这次还没有用量，稍等片刻。</p>
        <div v-if="usageRunning.length" class="tr-usage-block" data-testid="task-usage-running">
          <p class="tr-usage-k">进行中</p>
          <ul class="tr-usage-table">
            <li class="tr-usage-head"><span>环节</span><span>耗时</span><span>token</span><span>额度</span></li>
            <li v-for="row in usageRunning" :key="'run-' + row.name">
              <span>{{ row.name }}</span>
              <span>{{ row.duration }}</span>
              <span>{{ row.tokens }}</span>
              <span>{{ row.quota }}</span>
            </li>
          </ul>
        </div>
        <div v-if="usageSettled.length" class="tr-usage-block" data-testid="task-usage-settled">
          <p class="tr-usage-k">已结算</p>
          <ul class="tr-usage-table">
            <li class="tr-usage-head"><span>环节</span><span>耗时</span><span>token</span><span>额度</span></li>
            <li v-for="row in usageSettled" :key="'done-' + row.name">
              <span>{{ row.name }}</span>
              <span>{{ row.duration }}</span>
              <span>{{ row.tokens }}</span>
              <span>{{ row.quota }}</span>
            </li>
          </ul>
        </div>
      </div>
      <div class="tr-pipe" v-if="showPipe">
        <p class="tr-pipe-h">检查点管道</p>
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
    </section>

    <section v-show="panel === 'confirm'" data-testid="task-panel-confirm">
      <p
        v-if="task.status === 'PENDING' || task.status === 'RUNNING' || task.status === 'FAILED'"
        class="callout"
      >
        <template v-if="task.status === 'FAILED'">这次审校没能完成。请到「总览」从检查点继续。</template>
        <template v-else>正在审校。可到「总览」取消。</template>
      </p>
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
          <label v-for="p in pagedPartialPatches" :key="p.key" class="tr-check">
            <input type="checkbox" :checked="checkedKeys.includes(p.key)" @change="togglePatchKey(p.key)">
            <span>
              <span class="tr-check-cat">{{ patchCatLabel(p) }}</span>
              <PatchDiff :original="p.original" :proposed="p.proposed" />
            </span>
          </label>
          <PageBar v-model:page="partialPage" :size="TASK_LIST_PAGE_SIZE" :total="displayPatches.length" />
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
      <div
        class="callout"
        v-if="ready && (hasEdits || humanIssues.length || canContinueEdit || task.status === 'WAITING_ACCEPT')"
      >
        <template v-if="task.status === 'WAITING_ACCEPT' && hasEdits">
          系统给出 {{ displayPatches.length }} 处建议修改（候选稿，不会直接覆盖正式稿）。需要你处理的问题在下面；改稿和稿件对照在另外两个分类。
        </template>
        <template v-else-if="humanIssues.length">
          下面是需要你确认的 HUMAN_REQUIRED 项。其余问题和证据在「问题与证据」。
        </template>
        <template v-else-if="canContinueEdit">
          这次是{{ workflowName }}，只标问题、不改正文。若觉得这些问题没问题，可点「按这些问题改稿」。
        </template>
        <template v-else-if="task.status === 'WAITING_ACCEPT'">
          这次没有句子级的建议修改。可以全部接受或不采纳。
        </template>
      </div>
      <p
        class="muted"
        v-if="ready && !humanGroups.length && !humanIssues.length && !humanPatches.length"
        data-testid="task-empty-confirm"
      >{{ TASK_PANEL_EMPTY.confirm }}</p>
      <div class="tr-kinds" v-if="humanGroups.length">
        <h3>需你确认</h3>
        <p class="muted tr-hint">人机边界：补实验、改真实数据、关键引用最终选择等，须作者处理。</p>
        <div v-for="g in humanGroups" :key="g.key" class="tr-group" :data-kind="g.key">
          <button class="tr-group-h" :class="{ on: openKind === g.key }" type="button" @click="toggleKind(g.key)">
            <span>{{ g.label }}</span>
            <span class="muted">{{ g.items.length }} 处</span>
          </button>
          <div v-if="openKind === g.key" class="tr-group-body">
            <button
              v-for="item in pageSlice(g.items, confirmTaskPage).items"
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
              <PatchDiff
                :original="patchOf(item)?.original || ''"
                :proposed="patchOf(item)?.proposed || ''"
              />
            </button>
            <PageBar v-model:page="confirmTaskPage" :size="TASK_LIST_PAGE_SIZE" :total="g.items.length" />
          </div>
        </div>
      </div>
      <div class="panel" v-if="humanIssues.length">
        <h3>关联问题</h3>
        <button
          v-for="item in pageSlice(humanIssues, confirmIssuePage).items"
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
          <PriorityWhy
            v-if="item.high"
            :why-high="item.whyHigh"
            :suggest-fix="item.suggestFix"
            :evidence="item.evidenceView"
          />
          <PatchDiff
            :original="patchOf(item)?.original || ''"
            :proposed="patchOf(item)?.proposed || ''"
          />
        </button>
        <PageBar v-model:page="confirmIssuePage" :size="TASK_LIST_PAGE_SIZE" :total="humanIssues.length" />
      </div>
      <div class="panel" v-if="humanPatches.length">
        <h3>建议修改</h3>
        <p class="muted">候选稿对照，红为删除、绿为新增。不会直接覆盖正式稿。</p>
        <div v-for="g in humanPatchGroups" :key="g.key" class="patch-cat">
          <div class="patch-cat-h" :class="{ on: openCat === g.key }">
            <span>{{ g.label }}</span>
            <span class="muted">{{ g.items.length }} 处</span>
          </div>
          <div class="patch-cat-body">
            <button
              v-for="p in pageSlice(g.items, confirmPatchPage).items"
              :key="p.key"
              class="patch-now"
              :class="{ on: p.key === activePatchKey }"
              type="button"
              @click="openPatch(p)"
            >
              <PatchDiff :original="p.original" :proposed="p.proposed" />
            </button>
            <PageBar v-model:page="confirmPatchPage" :size="TASK_LIST_PAGE_SIZE" :total="g.items.length" />
          </div>
        </div>
      </div>
    </section>

    <section v-show="panel === 'issues'" data-testid="task-panel-issues">
      <div class="panel">
        <h3>问题</h3>
        <div v-if="!ready && !restIssues.length" class="muted">还在整理，稍等片刻。</div>
        <div v-else-if="!restIssues.length && humanIssues.length" class="muted">其余问题已放在「需你确认」。这次没有其他问题。</div>
        <div v-else-if="!restIssues.length" class="muted" data-testid="task-empty-issues">{{ TASK_PANEL_EMPTY.issues }}</div>
        <template v-else>
          <p class="muted tr-hint">HUMAN_REQUIRED 已在「需你确认」。点一条可到「稿件对照」看原文位置。</p>
          <div class="tr-filters" v-if="sevPills.length">
            <button
              v-for="pill in sevPills"
              :key="pill.key"
              class="tr-pill"
              :class="{ on: sevFilter === pill.key }"
              type="button"
              @click="setSevFilter(pill.key)"
            >{{ pill.label }} {{ pill.count }}</button>
          </div>
          <div class="tr-scroll">
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
                  v-for="item in pageSlice(g.items, issuePage).items"
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
                <PageBar v-model:page="issuePage" :size="TASK_LIST_PAGE_SIZE" :total="g.items.length" />
              </div>
            </div>
          </div>
        </template>
      </div>
      <div class="panel" style="margin-top:16px">
        <h3>证据</h3>
        <div v-if="!evidence.length" class="muted">这次没有证据。</div>
        <div v-else class="tr-group-body">
          <article v-for="ev in pageSlice(evidence, evidencePage).items" :key="ev.id || ev.claim" class="finding-card">
            <div class="finding-top">
              <span class="finding-agent">{{ ev.id || 'Evidence' }}</span>
              <span class="muted">{{ ev.status || ev.source }}</span>
            </div>
            <p class="finding-sum">{{ ev.claim || ev.excerpt }}</p>
            <p v-if="ev.excerpt && ev.excerpt !== ev.claim" class="finding-quote">{{ ev.excerpt }}</p>
            <p v-if="ev.source" class="finding-action">来源 {{ ev.source }}</p>
          </article>
          <PageBar v-model:page="evidencePage" :size="TASK_LIST_PAGE_SIZE" :total="evidence.length" />
        </div>
      </div>
    </section>

    <section v-show="panel === 'revise'" data-testid="task-panel-revise">
      <div class="tr-kinds" v-if="autoGroups.length">
        <h3>改稿计划</h3>
        <p class="muted tr-hint">可由系统改或人机协作。需你确认的项在「需你确认」。</p>
        <div v-for="g in autoGroups" :key="g.key" class="tr-group" :data-kind="g.key">
          <button class="tr-group-h" :class="{ on: openKind === g.key }" type="button" @click="toggleKind(g.key)">
            <span>{{ g.label }}</span>
            <span class="muted">{{ g.items.length }} 处</span>
          </button>
          <div v-if="openKind === g.key" class="tr-group-body">
            <button
              v-for="item in pageSlice(g.items, reviseTaskPage).items"
              :key="item.id || item.issueId + item.instruction"
              class="finding-card"
              type="button"
              @click="selectRevisionTask(item)"
            >
              <p class="finding-sum">{{ item.instruction }}</p>
              <PatchDiff
                :original="patchOf(item)?.original || ''"
                :proposed="patchOf(item)?.proposed || ''"
              />
            </button>
            <PageBar v-model:page="reviseTaskPage" :size="TASK_LIST_PAGE_SIZE" :total="g.items.length" />
          </div>
        </div>
      </div>
      <div class="panel" v-if="autoPatches.length">
        <h3>建议修改</h3>
        <p class="muted">红为删除、绿为新增。点一处会转到稿件对照。</p>
        <div v-for="g in patchGroups" :key="g.key" class="patch-cat">
          <div class="patch-cat-h" :class="{ on: openCat === g.key }">
            <span>{{ g.label }}</span>
            <span class="muted">{{ g.items.length }} 处</span>
          </div>
          <div class="patch-cat-body">
            <button
              v-for="p in pageSlice(g.items, revisePage).items"
              :key="p.key"
              class="patch-now"
              :class="{ on: p.key === activePatchKey }"
              type="button"
              @click="openPatch(p)"
            >
              <PatchDiff :original="p.original" :proposed="p.proposed" />
            </button>
            <PageBar v-model:page="revisePage" :size="TASK_LIST_PAGE_SIZE" :total="g.items.length" />
          </div>
        </div>
      </div>
      <p v-else-if="!autoGroups.length" class="muted" data-testid="task-empty-revise">{{ TASK_PANEL_EMPTY.revise }}</p>
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
    </section>

    <section v-show="panel === 'manuscript'" data-testid="task-panel-manuscript">
      <div class="panel" v-if="inspect.pagePreview">
        <PdfInspectPanel :inspect="inspect" @open="openSourceFile" />
      </div>
      <div class="panel">
        <h3>{{ hasEdits ? '抽出正文' : '原文里的位置' }}</h3>
        <p class="muted">
          {{ inspect.pagePreview ? '上图是 PDF 页与图表；下面是抽出正文。' : '标黄的就是当前选中的问题；带虚线的句子可点，查看建议。' }}
        </p>
        <p v-if="missHint" class="locate-miss">{{ missHint }}</p>
        <div ref="manuscriptEl" class="ms-body" @scroll="onMsScroll">
          <template v-for="(part, i) in bodyParts" :key="i">
            <mark
              v-if="part.hit || part.patchKey"
              :id="part.hit && i === firstHitIndex ? FINDING_HIT_ID : undefined"
              :class="{ hit: part.hit, 'patch-span': Boolean(part.patchKey), 'patch-on': part.patchOn }"
              :data-pk="part.patchKey || undefined"
              :data-anchor="part.hit ? findingAnchor : undefined"
              :tabindex="part.patchKey ? 0 : undefined"
              @click="part.patchKey && openPatchByKey(part.patchKey)"
              @keydown.enter.prevent="part.patchKey && openPatchByKey(part.patchKey)"
            >{{ part.text }}</mark>
            <span v-else>{{ part.text }}</span>
          </template>
          <span v-if="!officialText" class="muted" data-testid="task-empty-manuscript">{{ TASK_PANEL_EMPTY.manuscript }}</span>
        </div>
      </div>
    </section>

    <p class="muted" style="margin-top:8px">
      <router-link class="text-link" to="/history">返回审校记录</router-link>
      ·
      <router-link v-if="task.manuscriptId" class="text-link" :to="'/manuscripts/' + task.manuscriptId">打开论文</router-link>
    </p>

    <PatchPopover
      v-if="pop"
      :original="pop.original"
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
  autoKindGroups,
  buildReportMarkdown,
  CAT_META,
  FINDING_HIT_ID,
  SEV_META,
  TASK_LIST_PAGE_SIZE,
  TASK_PANEL_EMPTY,
  TASK_PANELS,
  catKeyOfIssue,
  findingAnchorOf,
  catKeyOfPatch,
  collect,
  expandRange,
  findAllIndexes,
  findRelatedIssue,
  humanKindGroups,
  humanRequiredIssues,
  humanRequiredPatches,
  isDoiToken,
  locateNeedle,
  meaningfulPatch,
  otherIssues,
  pageSlice,
  paintRanges,
  paragraphHunks,
  pickFindingNeedle,
  pipelineNodes,
  relatedPatch,
  revisablePatches,
  sevKey,
  severityLabel,
  showCheckpointPipeline,
  spotsNeedle,
  taskPanelCounts,
  taskPanelOf,
  toExportArtifacts,
  whyOf
} from '../review'
import { publicErrorMessage } from '../public-error'
import { splitUsageNodes, toUserUsage, usageNodeCols, usageSummaryCols } from '../task-usage'
import { toast } from '../toast'
import PatchPopover from '../components/PatchPopover.vue'
import PatchDiff from '../components/PatchDiff.vue'
import MergePreview from '../components/MergePreview.vue'
import PdfInspectPanel from '../components/PdfInspectPanel.vue'
import CopyId from '../components/CopyId.vue'
import PriorityWhy from '../components/PriorityWhy.vue'
import PageBar from '../components/PageBar.vue'

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
const findingsOpen = ref(true)
const sevFilter = ref('')
const openFindingGroup = ref('')
const openKind = ref('')
const confirmTaskPage = ref(1)
const confirmIssuePage = ref(1)
const confirmPatchPage = ref(1)
const issuePage = ref(1)
const evidencePage = ref(1)
const revisePage = ref(1)
const reviseTaskPage = ref(1)
const partialPage = ref(1)
const partialOpen = ref(false)
const checkedKeys = ref([])
const openCat = ref('')
const activePatchKey = ref('')
const pop = ref(null)
const popPos = ref({ top: 0, left: 0 })
const merge = ref(null)
const inspect = ref({})
const usage = ref({ nodes: [], durationMs: 0, tokens: 0, quota: 0, settled: false, inProgress: false })
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
const pipeNodes = computed(() => pipelineNodes(
  task.value.workflow,
  task.value.checkpointAgent,
  task.value.status,
  task.value.errorMessage
))
const showPipe = computed(() => showCheckpointPipeline(task.value.status) && pipeNodes.value.length > 0)
const userUsage = computed(() => toUserUsage(usage.value))
const usageCols = computed(() => usageSummaryCols(userUsage.value))
const usageSplit = computed(() => splitUsageNodes(userUsage.value))
const usageRunning = computed(() => usageSplit.value.running.map(usageNodeCols))
const usageSettled = computed(() => usageSplit.value.settled.map(usageNodeCols))
const panel = computed(() => taskPanelOf(route.query.tab))
const safeError = computed(() => publicErrorMessage(task.value.errorMessage))

const parsed = computed(() => collect(artifacts.value))
const issues = computed(() => parsed.value.issues)
const patches = computed(() => parsed.value.patches)
const tasks = computed(() => parsed.value.tasks)
const evidence = computed(() => parsed.value.evidence || [])
const humanIssues = computed(() => humanRequiredIssues(issues.value, tasks.value))
const restIssues = computed(() => otherIssues(issues.value, tasks.value))
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
  for (const item of restIssues.value) {
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
  for (const item of restIssues.value) {
    const key = sevKey(item.severity)
    counts[key] = (counts[key] || 0) + 1
  }
  const pills = [{ key: '', label: '全部', count: restIssues.value.length }]
  for (const m of SEV_META) {
    if (counts[m.key]) pills.push({ key: m.key, label: m.label, count: counts[m.key] })
  }
  return pills
})

const humanGroups = computed(() => humanKindGroups(tasks.value))
const autoGroups = computed(() => autoKindGroups(tasks.value))
const autoPatches = computed(() => revisablePatches(displayPatches.value, issues.value, tasks.value))
const humanPatches = computed(() => humanRequiredPatches(displayPatches.value, issues.value, tasks.value))
const humanTasks = computed(() => humanGroups.value.flatMap((g) => g.items))
const autoTasks = computed(() => autoGroups.value.flatMap((g) => g.items))
const panelCounts = computed(() => taskPanelCounts({
  artifacts: artifacts.value,
  humanIssues: humanIssues.value,
  humanTasks: humanTasks.value,
  restIssues: restIssues.value,
  evidence: evidence.value,
  autoTasks: autoTasks.value,
  autoPatches: autoPatches.value,
  displayPatches: displayPatches.value
}))
const pagedPartialPatches = computed(() => pageSlice(displayPatches.value, partialPage.value).items)
const findingAnchor = computed(() => findingAnchorOf(selected.value))

function groupPatches(list) {
  const groups = new Map(CAT_META.map((m) => [m.key, { ...m, items: [] }]))
  for (const p of list) {
    const key = catKeyOfPatch(p, issues.value)
    const g = groups.get(key) || groups.get('OTHER')
    g.items.push(p)
  }
  return CAT_META.map((m) => groups.get(m.key)).filter((g) => g.items.length)
}

const patchGroups = computed(() => groupPatches(autoPatches.value))
const humanPatchGroups = computed(() => groupPatches(humanPatches.value))

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

const firstHitIndex = computed(() => bodyParts.value.findIndex((part) => part.hit))

watch(issues, (list) => {
  if (!list.length) {
    selectedId.value = ''
    return
  }
  const hit = String(route.query.hit || '')
  if (hit && list.some((x) => x.id === hit)) {
    selectedId.value = hit
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

watch([humanGroups, autoGroups], ([human, auto]) => {
  const groups = [...human, ...auto]
  if (!groups.length) {
    openKind.value = ''
    return
  }
  if (!groups.some((g) => g.key === openKind.value)) {
    openKind.value = groups[0].key
  }
})

watch([patchGroups, humanPatchGroups], ([auto, human]) => {
  const groups = [...auto, ...human]
  if (!groups.length) {
    if (!openCat.value) return
    openCat.value = ''
    return
  }
  if (!groups.some((g) => g.key === openCat.value)) {
    openCat.value = groups[0].key
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
    usage.value = await api('/reviews/' + route.params.id + '/usage')
  } catch {
    usage.value = {
      nodes: [],
      durationMs: 0,
      tokens: 0,
      quota: 0,
      settled: false,
      inProgress: ['PENDING', 'RUNNING'].includes(task.value.status)
    }
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

function setPanel(key) {
  const tab = taskPanelOf(key)
  const query = { ...route.query, tab }
  if (tab !== 'manuscript') {
    delete query.hit
    delete query.anchor
  }
  router.replace({ path: route.path, query, hash: tab === 'manuscript' ? route.hash : '' })
}

async function jumpToManuscript(item) {
  const hit = item?.id ? String(item.id) : ''
  const anchor = findingAnchorOf(item)
  const query = { ...route.query, tab: 'manuscript' }
  if (hit) query.hit = hit
  else delete query.hit
  if (anchor) query.anchor = anchor
  else delete query.anchor
  await router.replace({ path: route.path, query, hash: '#' + FINDING_HIT_ID })
  await nextTick()
  await scrollToSelector('#' + FINDING_HIT_ID + ', mark.hit', true)
}

async function selectFinding(item) {
  closePop()
  findingsOpen.value = true
  openFindingGroup.value = sevKey(item.severity)
  selectedId.value = item.id
  const k = catKeyOfIssue(item)
  if (patchGroups.value.some((g) => g.key === k)) openCat.value = k
  await jumpToManuscript(item)
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

function patchOf(item) {
  return relatedPatch(item, displayPatches.value)
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
  if (panel.value !== 'manuscript') setPanel('manuscript')
  pop.value = {
    original: p.original || '',
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
  await scrollToSelector('#' + FINDING_HIT_ID + ', mark.hit', smooth)
}

async function scrollToSelector(sel, smooth) {
  await nextTick()
  const box = manuscriptEl.value
  const mark = box?.querySelector(sel)
  if (!box || !mark) return
  const top = mark.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop - 88
  const nextTop = Math.max(0, top)
  if (typeof box.scrollTo === 'function') {
    box.scrollTo({ top: nextTop, behavior: smooth ? 'smooth' : 'auto' })
    return
  }
  box.scrollTop = nextTop
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
