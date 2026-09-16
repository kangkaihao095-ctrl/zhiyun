<template>
  <div class="ops-board ops-dense ops-fill" data-testid="obs-board">
    <header class="ops-top">
      <div class="ops-brand">
        <div class="ops-brand-copy">
          <h1 class="ops-product">智云观测</h1>
          <p class="ops-product-note">{{ scopeNote }}</p>
        </div>
      </div>
      <div class="ops-top-tools">
        <ThemeToggle />
        <span v-if="syncedHint" class="ops-clock">{{ syncedHint }}</span>
        <button
          class="ops-icon-btn"
          type="button"
          data-testid="ops-refresh"
          :disabled="loading"
          title="刷新 · 自动 15s"
          @click="refresh"
        >{{ loading ? '同步中…' : '刷新' }}</button>
        <button class="ops-icon-btn ops-back" type="button" data-testid="ops-back-product" @click="leaveOps">返回登录</button>
      </div>
    </header>

    <div class="ops-body">
      <p class="ops-err" v-if="error">{{ error }}</p>

      <section class="ops-filter-card">
        <div class="ops-filter-row">
          <span class="ops-filter-label">时间范围</span>
          <div class="ops-range" role="tablist" aria-label="时间范围">
            <button
              v-for="r in OBS_RANGES"
              :key="r.key"
              type="button"
              class="ops-range-btn"
              :class="{ on: range === r.key }"
              :data-testid="'ops-range-' + r.key"
              @click="setRange(r.key)"
            >{{ r.label }}</button>
          </div>
          <span class="ops-date-range">{{ periodLabel }}</span>
        </div>
      </section>

      <section class="ops-alerts" data-testid="ops-alerts">
        <p class="ops-alerts__caption">{{ alertsCaption }}</p>
        <div v-if="alertItems.length" class="ops-alerts__list">
          <span
            v-for="row in alertItems"
            :key="row.id"
            class="ops-alert"
            :class="'is-' + row.tone"
            :data-testid="'ops-alert-' + row.id"
          >
            <b>{{ row.title }}</b>
            <em>{{ fmt(row.count) }}</em>
          </span>
        </div>
        <p v-else class="ops-alerts__idle">窗口规则未触发（不是 pager）</p>
      </section>

      <section class="ops-panel ops-duty" data-testid="ops-duty">
        <div class="ops-panel-title"><span class="ops-dot ops-dot--danger" />值班总览</div>
        <p class="ops-panel-desc">{{ rangeLabel }} · 积压 / 耗时分位 / 跨租户 · {{ tokenDeltaHint }}</p>
        <div class="ops-mini-kpis ops-mini-kpis--8">
          <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(backlog.pending) }}</b><span>PENDING</span></div></div>
          <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(backlog.running) }}</b><span>RUNNING</span></div></div>
          <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(backlog.waitingAccept) }}</b><span>待确认</span></div></div>
          <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(backlog.leasesHeld) }}</b><span>lease 持有</span></div></div>
          <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(dutyP50) }}</b><span>P50</span></div></div>
          <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(dutyP95) }}</b><span>P95</span></div></div>
          <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(crossTenant.activeTenants) }}</b><span>活跃租户</span></div></div>
          <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(crossTenant.failedTenants) }}</b><span>失败租户</span></div></div>
        </div>
      </section>

      <section class="ops-kpi" data-testid="ops-kpi">
        <article v-for="card in cards" :key="card.key" class="ops-stat">
          <div class="ops-stat-name">{{ card.label }}</div>
          <div class="ops-stat-body">
            <strong class="ops-stat-val" :class="toneOf(card.key)">{{ displayValue(card) }}</strong>
            <svg
              v-if="card.spark.hasData"
              class="ops-spark"
              :viewBox="'0 0 ' + card.spark.width + ' ' + card.spark.height"
              aria-hidden="true"
            >
              <path :d="card.spark.area" class="ops-spark-area" :class="'spark-' + card.key" />
              <path :d="card.spark.line" class="ops-spark-line" :class="'spark-' + card.key" fill="none" />
            </svg>
          </div>
          <p class="ops-stat-hint">{{ card.hint }}</p>
        </article>
      </section>

      <div class="ops-section-title"><span class="ops-bar" />运行概览</div>
      <p class="ops-section-desc">窗口内任务创建、完成与完成率。完成占比 = 已完成 + 待确认 / 任务数，不是 SLA。</p>

      <div class="ops-split ops-split--align">
        <section class="ops-panel ops-split__main">
          <div class="ops-panel-title"><span class="ops-dot" />任务完成</div>
          <p class="ops-panel-desc">{{ rangeLabel }} · 创建 / 完成柱，完成率折线</p>
          <div class="ops-chart-fill">
            <OpsComboChart
              v-if="trendLabels.length"
              fill
              :labels="trendLabels"
              :started-values="trendStarted"
              :succeeded-values="trendSucceeded"
              :failed-values="trendFailed"
              :rate-values="trendRate"
              :format="fmt"
              :height="280"
            />
            <div v-else class="ops-placeholder">这个窗口没有样本</div>
          </div>
          <p v-if="trendInsight" class="ops-insight">{{ trendInsight }}</p>
        </section>

        <section class="ops-panel ops-split__side" data-testid="ops-tenants">
          <div class="ops-panel-title">
            <span class="ops-dot ops-dot--info" />
            租户
          </div>
          <p class="ops-panel-desc">{{ tenants.length }} 个实验室 · 检索后分页，全平台汇总为默认。跨租户失败 {{ fmt(crossTenant.failedTenants) }} 户</p>
          <SearchPager
            v-model:q="tenantQ"
            v-model:page="tenantPage"
            :size="TENANT_PAGE_SIZE"
            :total="filteredTenants.length"
            placeholder="搜索实验室名"
            test-id="ops-tenant-q"
            @search="tenantPage = 1"
          />
          <div class="ops-tenant-bar">
            <button
              type="button"
              class="ops-tenant-chip"
              :class="{ on: !tenantId }"
              data-testid="ops-tenant-all"
              @click="setTenant('')"
            >全部汇总</button>
            <div class="ops-tenant-head" aria-hidden="true">
              <span>实验室</span>
              <span>任务</span>
              <span>完成占比</span>
              <span>失败</span>
              <span>lease</span>
              <span>token</span>
            </div>
            <button
              v-for="row in pagedTenants"
              :key="row.tenantId"
              type="button"
              class="ops-tenant-row"
              :class="{ on: tenantId === String(row.tenantId) }"
              :data-testid="'ops-tenant-' + row.tenantId"
              @click="setTenant(row.tenantId)"
            >
              <span class="ops-tenant-name">{{ row.tenantName }}</span>
              <span>{{ fmt(row.tasks) }}</span>
              <span>{{ row.successRatePct }}%</span>
              <span>{{ fmt(row.failed) }}</span>
              <span>{{ fmt(row.leasesHeld) }}</span>
              <span>{{ fmt(row.tokens) }}</span>
            </button>
            <p v-if="!filteredTenants.length" class="ops-placeholder ops-placeholder--compact">这个窗口没有租户样本</p>
          </div>
        </section>
      </div>

      <div class="ops-section-title"><span class="ops-bar ops-bar--info" />Agent / 工具</div>
      <p class="ops-section-desc">窗口内 span 与工具调用。没有调用就是 0，不编假的首 token。运行观测，非 SLA。</p>

      <div class="ops-split">
        <section class="ops-panel" data-testid="ops-tools">
          <div class="ops-panel-title"><span class="ops-dot ops-dot--info" />Tool calls</div>
          <p class="ops-panel-desc">{{ rangeLabel }} · 按工具计数</p>
          <div class="ops-mini-kpis ops-mini-kpis--2" data-testid="ops-tool-fail">
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(toolFailed) }}</b><span>工具失败</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(spanFailed) }}</b><span>节点失败</span></div></div>
          </div>
          <OpsBarChart
            v-if="toolRows.length"
            :labels="toolRows.map((r) => r.name)"
            :values="toolRows.map((r) => r.calls)"
            :format="fmt"
            color="var(--ops-cyan)"
            :height="200"
          />
          <div v-else class="ops-placeholder">0 次工具调用</div>
          <p class="ops-chart-note" data-testid="ops-tools-note">{{ toolNote }}</p>
        </section>
        <section class="ops-panel" data-testid="ops-agent-tools">
          <div class="ops-panel-title"><span class="ops-dot" />按 Agent 的工具调用</div>
          <p class="ops-panel-desc">{{ rangeLabel }} · 次数</p>
          <OpsBarChart
            v-if="toolByAgentRows.length"
            :labels="toolByAgentRows.map((r) => r.name)"
            :values="toolByAgentRows.map((r) => r.calls)"
            :format="fmt"
            color="var(--ops-blue)"
            :height="200"
          />
          <div v-else class="ops-placeholder">0 次工具调用</div>
          <p class="ops-chart-note" data-testid="ops-agent-tools-note">{{ agentToolNote }}</p>
        </section>
      </div>

      <div class="ops-split">
        <section class="ops-panel" data-testid="ops-agent-duration">
          <div class="ops-panel-title"><span class="ops-dot ops-dot--info" />按 Agent 成功率 / 耗时</div>
          <p class="ops-panel-desc">{{ rangeLabel }} · 平均 duration</p>
          <OpsBarChart
            v-if="agentRows.length"
            :labels="agentRows.map((r) => r.name)"
            :values="agentRows.map((r) => r.avgDurationMs)"
            :format="fmtDuration"
            color="var(--ops-cyan)"
            :height="200"
          />
          <div v-else class="ops-placeholder">这个窗口没有 Agent span</div>
          <p class="ops-chart-note" data-testid="ops-agent-duration-note">{{ agentDurationNote }}</p>
          <p v-if="agentInsight" class="ops-insight ops-insight--info">{{ agentInsight }}</p>
        </section>
        <section class="ops-panel" data-testid="ops-agent-tokens">
          <div class="ops-panel-title"><span class="ops-dot" />Token</div>
          <p class="ops-panel-desc">{{ rangeLabel }} · 窗口内合计</p>
          <OpsBarChart
            v-if="agentRows.length"
            :labels="agentRows.map((r) => r.name)"
            :values="agentRows.map((r) => r.tokens)"
            :format="fmt"
            color="var(--ops-blue)"
            :height="200"
          />
          <div v-else class="ops-placeholder">这个窗口没有 token 样本</div>
          <p class="ops-chart-note" data-testid="ops-agent-tokens-note">{{ agentTokenNote }}</p>
        </section>
      </div>

      <div class="ops-quad" data-testid="ops-runtime">
        <section class="ops-panel ops-panel--kpis" data-testid="ops-llm">
          <div class="ops-panel-title"><span class="ops-dot" />LLM</div>
          <p class="ops-panel-desc">窗口 span · 首 token 分位 · 非 SLA</p>
          <div class="ops-mini-kpis ops-mini-kpis--8">
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(llm.calls) }}</b><span>calls</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(llm.tokens) }}</b><span>tokens</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(llm.promptTokens) }}</b><span>prompt</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(llm.completionTokens) }}</b><span>completion</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(llm.structuredFail) }}</b><span>结构化失败</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(llm.avgDurationMs) }}</b><span>窗口耗时</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(llm.p50Ms) }}</b><span>P50</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(llm.p95Ms) }}</b><span>P95</span></div></div>
          </div>
          <div class="ops-mini-kpis ops-mini-kpis--2" data-testid="ops-llm-ttft">
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(llm.firstTokenP50Ms) }}</b><span>首 token P50</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(llm.firstTokenP95Ms) }}</b><span>首 token P95</span></div></div>
          </div>
        </section>
        <section class="ops-panel ops-panel--kpis" data-testid="ops-citation">
          <div class="ops-panel-title"><span class="ops-dot ops-dot--chart" />引用核验</div>
          <p class="ops-panel-desc">Java lookupDoi · 编 DOI 被丢</p>
          <div class="ops-mini-kpis ops-mini-kpis--4">
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(citation.lookupDoi) }}</b><span>lookupDoi</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(citation.lookupOk) }}</b><span>命中</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ citation.notVerifiedPct }}%</b><span>NOT_VERIFIED</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(citation.inventedDoiDropped) }}</b><span>编 DOI 丢弃</span></div></div>
          </div>
        </section>
        <section class="ops-panel ops-panel--chart" data-testid="ops-rag">
          <div class="ops-panel-title"><span class="ops-dot ops-dot--info" />RAG</div>
          <p class="ops-panel-desc">运行观测，非 SLA · 空召回不是召回率</p>
          <div class="ops-chart-fill ops-chart-fill--rag">
            <OpsBarChart
              fill
              :labels="['private', 'public', 'kNN']"
              :values="[rag.privateRetrievals, rag.publicRetrievals, rag.knn]"
              :format="fmt"
              color="var(--ops-cyan)"
              :height="200"
            />
          </div>
          <div class="ops-mini-kpis ops-mini-kpis--4" data-testid="ops-rag-empty">
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(rag.emptyHits) }}</b><span>空召回</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(rag.privateEmptyHits) }}</b><span>private 空</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(rag.publicEmptyHits) }}</b><span>public 空</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(rag.knnEmptyHits) }}</b><span>kNN 空</span></div></div>
          </div>
          <div v-if="rag.hasDuration" class="ops-mini-kpis ops-mini-kpis--3" data-testid="ops-rag-duration">
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(rag.privateAvgDurationMs) }}</b><span>private 耗时</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(rag.publicAvgDurationMs) }}</b><span>public 耗时</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ durationKpi(rag.knnAvgDurationMs) }}</b><span>kNN 耗时</span></div></div>
          </div>
        </section>
        <section class="ops-panel ops-panel--kpis" data-testid="ops-harness">
          <div class="ops-panel-title"><span class="ops-dot ops-dot--danger" />Harness</div>
          <p class="ops-panel-desc">lease / fencing · 非 SLA</p>
          <div class="ops-mini-kpis ops-mini-kpis--4">
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(harness.leasesHeld) }}</b><span>lease 持有</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(harness.leasesExpired) }}</b><span>lease 过期</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(harness.fencingRaised) }}</b><span>fencing 抬升</span></div></div>
            <div class="ops-mini-kpi"><div class="ops-mini-kpi__body"><b>{{ fmt(harness.fencingRejected) }}</b><span>fencing 拒绝</span></div></div>
          </div>
        </section>
      </div>

      <div class="ops-section-title"><span class="ops-bar ops-bar--danger" />失败与追踪</div>
      <p class="ops-section-desc">失败码按窗口计数；Trace 是相对任务开始的瀑布，点一条看 span。</p>

      <div class="ops-split ops-split--align ops-split--bottom" data-testid="ops-fail-trace">
        <section class="ops-panel">
          <div class="ops-panel-title"><span class="ops-dot ops-dot--danger" />失败码</div>
          <p class="ops-panel-desc">{{ rangeLabel }} · 次数与占比</p>
          <div class="ops-chart-fill">
            <OpsBarChart
              v-if="codeRows.length"
              layout="horizontal"
              fill
              :labels="codeRows.map((r) => r.label)"
              :values="codeRows.map((r) => r.count)"
              :format="fmt"
              color="var(--ops-red)"
              :height="codeChartHeight"
              :label-max="14"
            />
            <div v-else class="ops-placeholder">这个窗口没有失败码</div>
          </div>
          <p v-if="codeInsight" class="ops-insight ops-insight--danger">{{ codeInsight }}</p>
        </section>

        <section class="ops-panel ops-trace-panel">
          <div class="ops-panel-title"><span class="ops-dot ops-dot--chart" />Trace</div>
          <p class="ops-panel-desc">{{ rangeLabel }} · waterfall · 点一条看时间线，hover span 看摘要</p>
          <SearchPager
            v-model:q="traceQ"
            v-model:page="tracePage"
            :size="TRACE_PAGE_SIZE"
            :total="filteredTraces.length"
            placeholder="搜索任务号 / 实验室 / 状态 / Agent"
            test-id="ops-trace-q"
            @search="tracePage = 1"
          />
          <div class="ops-trace-split">
            <div class="ops-trace-list">
              <p v-if="!filteredTraces.length" class="ops-placeholder ops-placeholder--compact">这个窗口没有任务</p>
              <button
                v-for="row in pagedTraces"
                :key="row.taskId"
                type="button"
                class="ops-trace-row"
                :class="{ on: selectedId === row.taskId }"
                :data-testid="'ops-trace-' + row.taskId"
                @click="openTrace(row.taskId)"
              >
                <span class="ops-dot-status" :data-s="row.status" />
                <span class="ops-trace-id">{{ row.taskId }}</span>
                <span class="ops-trace-wf">{{ row.tenantName ? row.tenantName + ' · ' + row.workflowName : row.workflowName }}</span>
                <span class="ops-trace-ms">{{ row.durationText }}</span>
                <span v-if="row.firstTokenText" class="ops-trace-ttft">{{ row.firstTokenText }}</span>
                <span v-if="row.errorLabel" class="ops-code">{{ row.errorLabel }}</span>
              </button>
            </div>
            <div class="ops-trace-view" data-testid="ops-waterfall" v-if="selectedId">
              <p class="ops-err" v-if="traceError">{{ traceError }}</p>
              <TraceWaterfall v-if="traceNodes.length" :nodes="traceNodes" />
              <p v-else-if="!traceError" class="ops-placeholder ops-placeholder--compact">这条任务还没有 Agent span</p>
            </div>
            <p v-else class="ops-placeholder ops-trace-hint">点一条任务看时间线</p>
          </div>
        </section>
      </div>

      <footer class="ops-footer-note">
        <b>口径说明：</b>
        本页是运行观测，非 SLA。时间窗内统计任务创建 / 完成 / 失败；完成占比 =（已完成 + 待确认）÷ 任务数；
        lease 为未过期执行租约；checkpoint 跳过为从检查点续跑跳过的节点；
        token 与耗时来自 Agent span；失败码按窗口聚合；Trace 为相对任务开始的瀑布。
        评估集 Recall 与要点命中不在此页。首 token 为流式首个 delta / 非流式完整响应到达，不是 SLA。工具调用来自 agent_span.tool_calls，按工具计数。
        LLM 窗口耗时、P50/P95、RAG 耗时与空召回、工具失败、积压均来自同一套窗口表。
        告警条是窗口规则，不是 SLA、不是 pager。
      </footer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, qs } from '../api'
import { formatDurationMs } from '../review.js'
import { PIPE_STATE_LABEL } from '../review.js'
import {
  agentDurationRows,
  clockText,
  durationKpi,
  errorCodeRows,
  filterTenants,
  filterTraces,
  kpiCards,
  OBS_RANGES,
  rangeLabelOf,
  rangeOf,
  recentDurationRows,
  spanState,
  TENANT_PAGE_SIZE,
  TRACE_PAGE_SIZE,
  toolCallRows,
  tenantRows,
  chartFootnote,
  alertRows,
  tokenDeltaText
} from '../observability.js'
import { pageSlice } from '../review.js'
import TraceWaterfall from '../components/TraceWaterfall.vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import OpsComboChart from '../components/OpsComboChart.vue'
import OpsBarChart from '../components/OpsBarChart.vue'
import SearchPager from '../components/SearchPager.vue'

const route = useRoute()
const router = useRouter()
const data = ref<Record<string, any>>({})
const error = ref('')
const loading = ref(false)
const selectedId = ref('')
const trace = ref<{ nodes?: any[] }>({ nodes: [] })
const traceError = ref('')
const refreshedAt = ref(clockText())
let timer = 0

const tenantQ = ref('')
const tenantPage = ref(1)
const traceQ = ref('')
const tracePage = ref(1)
const range = computed(() => rangeOf(route.query.range))
const tenantId = computed(() => String(route.query.tenantId || ''))
const rangeLabel = computed(() => rangeLabelOf(range.value))
const cards = computed(() => kpiCards(data.value))
const tenants = computed(() => tenantRows(data.value.tenants))
const filteredTenants = computed(() => filterTenants(tenants.value, tenantQ.value))
const pagedTenants = computed(() => pageSlice(filteredTenants.value, tenantPage.value, TENANT_PAGE_SIZE).items)
const toolRows = computed(() => toolCallRows(data.value.tools?.byTool))
const toolByAgentRows = computed(() => toolCallRows(data.value.tools?.byAgent))
const toolFailed = computed(() => Number(data.value.tools?.failed || 0))
const spanFailed = computed(() => Number(data.value.tools?.spanFailed || 0))
const llm = computed(() => data.value.llm || {})
const citation = computed(() => data.value.citation || {})
const rag = computed(() => data.value.rag || {})
const harness = computed(() => data.value.harness || {})
const backlog = computed(() => data.value.backlog || data.value.tasks || {})
const crossTenant = computed(() => data.value.crossTenant || data.value.kpis || {})
const alertItems = computed(() => alertRows(data.value.alerts))
const alertsCaption = computed(() => String(data.value.alerts?.caption || '窗口规则，不是 SLA、不是 pager。'))
const dutyP50 = computed(() => data.value.kpis?.p50Ms ?? data.value.llm?.p50Ms)
const dutyP95 = computed(() => data.value.kpis?.p95Ms ?? data.value.llm?.p95Ms)
const tokenDeltaHint = computed(() => tokenDeltaText(data.value.kpis?.tokenDeltaPct ?? data.value.llm?.tokenDeltaPct))
const scopeNote = computed(() => {
  if (data.value?.scope === 'tenant' && data.value?.tenantName) {
    return data.value.tenantName + ' · 运行观测，非 SLA'
  }
  return '全平台运行观测，非 SLA'
})
const codeRows = computed(() => errorCodeRows(data.value.errorCodes))
const agentRows = computed(() => agentDurationRows(data.value.agents))
const toolNote = computed(() => chartFootnote('tool', toolRows.value.map((r) => r.name)))
const agentToolNote = computed(() => chartFootnote('agent-tools', toolByAgentRows.value.map((r) => r.name)))
const agentDurationNote = computed(() => chartFootnote('agent-duration', agentRows.value.map((r) => r.name)))
const agentTokenNote = computed(() => chartFootnote('agent-token', agentRows.value.map((r) => r.name)))
const recentRows = computed(() => recentDurationRows(data.value.recent))
const filteredTraces = computed(() => filterTraces(recentRows.value, traceQ.value))
const pagedTraces = computed(() => pageSlice(filteredTraces.value, tracePage.value, TRACE_PAGE_SIZE).items)
const trendRows = computed(() => Array.isArray(data.value.trend) ? data.value.trend : [])
const trendLabels = computed(() => trendRows.value.map((row) => String(row?.bucket || '')))
const trendStarted = computed(() => trendRows.value.map((row) => Number(row?.started || 0)))
const trendSucceeded = computed(() => trendRows.value.map((row) => Number(row?.succeeded || 0)))
const trendFailed = computed(() => trendRows.value.map((row) => Number(row?.failed || 0)))
const trendRate = computed(() => trendStarted.value.map((started, i) => {
  if (started <= 0) return 0
  return Math.round((trendSucceeded.value[i] * 100) / started)
}))
const periodLabel = computed(() => {
  const rows = trendLabels.value.filter(Boolean)
  if (rows.length >= 2) return `${rows[0]} 至 ${rows[rows.length - 1]}`
  if (rows.length === 1) return rows[0]
  return rangeLabel.value
})
const syncedHint = computed(() => {
  if (!refreshedAt.value) return ''
  return loading.value ? `同步中…（${refreshedAt.value}）` : `同步于 ${refreshedAt.value}`
})
const trendInsight = computed(() => {
  const card = cards.value[0]
  const rate = cards.value[1]
  const failed = cards.value[4]
  if (!card) return ''
  const scope = data.value?.scope === 'tenant' ? '该实验室' : '全部实验室'
  return `${periodLabel.value}${scope}创建 ${displayValue(card)} 个任务，完成占比 ${rate?.value || '0%'}，失败 ${failed?.value || '0'}。运行观测，非 SLA。`
})
const agentInsight = computed(() => {
  const top = [...agentRows.value].sort((a, b) => b.avgDurationMs - a.avgDurationMs)[0]
  if (!top) return ''
  return `耗时最高「${top.name}」平均 ${top.durationText}，${fmt(top.runs)} 次 · ${fmt(top.tokens)} token。`
})
const codeInsight = computed(() => {
  const top = codeRows.value[0]
  if (!top) return ''
  return `最高「${top.label}」${fmt(top.count)} 次，占失败码 ${top.share}%。`
})
const codeChartHeight = computed(() => Math.max(280, codeRows.value.length * 36))
const traceNodes = computed(() => (Array.isArray(trace.value?.nodes) ? trace.value.nodes : []).map((n) => ({
  ...n,
  state: spanState(n.status),
  label: PIPE_STATE_LABEL[spanState(n.status)] || n.status || '',
  meta: nodeMeta(n)
})))

function fmt(n) {
  return Math.round(Number(n) || 0).toLocaleString('zh-CN')
}

function fmtDuration(n) {
  return formatDurationMs(n) || '0ms'
}

function displayValue(card) {
  if (!card) return '-'
  if (card.key === 'rate') return card.value
  const raw = String(card.value ?? '')
  if (raw.endsWith('%')) return raw
  const num = Number(raw)
  return Number.isFinite(num) ? fmt(num) : raw
}

function toneOf(key) {
  if (key === 'failed') return 'tone-danger'
  if (key === 'tasks' || key === 'rate') return 'tone-brand'
  return ''
}

function nodeMeta(node) {
  return [
    formatDurationMs(node.durationMs),
    node.firstTokenMs != null && node.firstTokenMs !== '' ? `首 token ${formatDurationMs(node.firstTokenMs)}` : '',
    node.tokens != null && node.tokens !== '' ? `${node.tokens} token` : '',
    node.toolName || '',
    node.checkpoint ? 'checkpoint' : '',
    node.skipped ? 'checkpoint 跳过' : '',
    node.fencingToken != null && node.fencingToken !== '' ? `fence ${node.fencingToken}` : '',
    node.skillVersion ? `skill ${node.skillVersion}` : '',
    node.promptVersion ? `prompt ${node.promptVersion}` : '',
    node.errorCode || ''
  ].filter(Boolean).join(' · ')
}

function setRange(key) {
  router.replace({ path: '/ops', query: { ...route.query, range: rangeOf(key) } })
}

function setTenant(id) {
  const next = { ...route.query }
  if (id === '' || id == null) {
    delete next.tenantId
  } else {
    next.tenantId = String(id)
  }
  router.replace({ path: '/ops', query: next })
}

function leaveOps() {
  sessionStorage.removeItem('token')
  sessionStorage.removeItem('zhiyun-enter')
  router.push('/login')
}

async function load(manual = false) {
  if (manual) loading.value = true
  try {
    data.value = await api('/ops/observability' + qs({
      range: range.value,
      tenantId: tenantId.value || undefined
    })) as Record<string, any>
    error.value = ''
    refreshedAt.value = clockText()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '没能加载运行观测'
    throw e
  } finally {
    if (manual) loading.value = false
  }
}

function refresh() {
  load(true).catch(() => {})
}

async function openTrace(id) {
  selectedId.value = id
  traceError.value = ''
  try {
    trace.value = await api('/reviews/' + id + '/trace')
  } catch (e: unknown) {
    trace.value = { nodes: [] }
    traceError.value = e instanceof Error ? e.message : '没能加载这条 Trace'
  }
}

watch(range, () => {
  tenantPage.value = 1
  tracePage.value = 1
  load().catch(() => {})
})

watch(tenantQ, () => {
  tenantPage.value = 1
})

watch(traceQ, () => {
  tracePage.value = 1
})

watch(tenantId, () => {
  selectedId.value = ''
  trace.value = { nodes: [] }
  tracePage.value = 1
  load().catch(() => {})
})

onMounted(() => {
  load(true).catch(() => {})
  timer = window.setInterval(() => { load().catch(() => {}) }, 15000)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>
