import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  agentDurationRows,
  durationKpi,
  errorCodeRows,
  kpiCards,
  OBSERVABILITY_CAPTION,
  rangeOf,
  filterTenants,
  filterTraces,
  TENANT_PAGE_SIZE,
  TRACE_PAGE_SIZE,
  isMonthRange,
  toolAxisLabel,
  wrapAxisLabel,
  axisTickLines,
  chartFootnote,
  recentDurationRows,
  sparseAxisTick,
  sparkline,
  trendChart,
  alertRows,
  tokenDeltaText
} from '../src/observability.js'
import { errorCodeLabel } from '../src/public-error'
import { pageSlice, taskPanelOf } from '../src/review.js'

const api = vi.fn()
vi.mock('../src/api.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api.js')>()
  return {
    ...actual,
    api: (...args: unknown[]) => api(...args)
  }
})

import OpsConsole from '../src/views/OpsConsole.vue'
import History from '../src/views/History.vue'
import OpsBarChart from '../src/components/OpsBarChart.vue'

const Blank = defineComponent({ template: '<div />' })

const DASHBOARD = {
  caption: OBSERVABILITY_CAPTION,
  scope: 'all',
  tenantId: null,
  tenantName: '',
  window: { range: '7d', taskLimit: 500, recentLimit: 20 },
  kpis: {
    tasks: 3,
    successRatePct: 67,
    inProgress: 1,
    leasesHeld: 1,
    failed: 1,
    checkpointSkipped: 1,
    tokens: 4000,
    tenants: 2,
    p50Ms: 800,
    p95Ms: 1200,
    pending: 1,
    running: 0,
    waitingAccept: 1,
    activeTenants: 12,
    failedTenants: 1,
    tokenDeltaPct: 10
  },
  tasks: { started: 3, succeeded: 1, waitingAccept: 1, failed: 1, running: 0, pending: 0 },
  tokens: 4000,
  trend: [
    { bucket: '09-10', started: 1, succeeded: 1, failed: 0 },
    { bucket: '09-11', started: 2, succeeded: 1, failed: 1 }
  ],
  errorCodes: [
    { code: 'timeout', count: 1 },
    { code: 'structured_output', count: 1 }
  ],
  harness: { leasesHeld: 1, leasesExpired: 0, fencingRaised: 2, fencingRejected: 0, checkpointSkipped: 1 },
  leases: [{
    taskId: 'ZYT1',
    status: 'RUNNING',
    owner: 'host:1:abc',
    expireAt: '2026-09-11T10:00:00Z',
    fencingToken: 4
  }],
  agents: [{
    agent: 'CITATION_INTEGRITY',
    name: '引用核验',
    runs: 3,
    failed: 1,
    tokens: 4000,
    skipped: 1,
    avgDurationMs: 850
  }],
  recent: [{
    taskId: 'ZYT1',
    tenantId: 1,
    tenantName: '演示实验室',
    workflow: 'CITATION_ONLY',
    workflowName: '引用核验',
    status: 'RUNNING',
    durationMs: 1200,
    tokens: 2000,
    fencingToken: 4,
    errorCode: '',
    checkpointAgent: 'CITATION_INTEGRITY'
  }, {
    taskId: 'ZYT2',
    tenantId: 2,
    tenantName: '林的实验室',
    workflow: 'QUICK_REVIEW',
    workflowName: '快速审读',
    status: 'FAILED',
    durationMs: 3200,
    tokens: 1800,
    fencingToken: 2,
    errorCode: 'timeout',
    checkpointAgent: 'ACADEMIC_STYLE'
  },
  ...Array.from({ length: 12 }, (_, i) => ({
    taskId: `ZYT${10 + i}`,
    tenantId: 1,
    tenantName: '演示实验室',
    workflow: 'CITATION_ONLY',
    workflowName: '引用核验',
    status: 'DONE',
    durationMs: 800 + i,
    tokens: 100,
    fencingToken: 1,
    errorCode: '',
    checkpointAgent: 'CITATION_INTEGRITY'
  }))],
  tenants: [
    {
      tenantId: 1,
      tenantName: '演示实验室',
      tasks: 2,
      successRatePct: 50,
      failed: 0,
      inProgress: 1,
      leasesHeld: 1,
      tokens: 2200,
      errorCodes: []
    },
    {
      tenantId: 2,
      tenantName: '林的实验室',
      tasks: 1,
      successRatePct: 0,
      failed: 1,
      inProgress: 0,
      leasesHeld: 0,
      tokens: 1800,
      errorCodes: [{ code: 'timeout', count: 1 }]
    },
    ...Array.from({ length: 10 }, (_, i) => ({
      tenantId: 10 + i,
      tenantName: `${i}号光学实验室`,
      tasks: 8 + i,
      successRatePct: 40 + i,
      failed: i,
      inProgress: 0,
      leasesHeld: 0,
      tokens: 1000 * (i + 1),
      errorCodes: []
    }))
  ],
  tools: {
    failed: 2,
    spanFailed: 1,
    byTool: [
      { name: 'AcademicSearch', calls: 12, ok: 10, failed: 2, successRatePct: 83, avgDurationMs: 40 },
      { name: 'WebSearch', calls: 3, ok: 3, failed: 0, successRatePct: 100, avgDurationMs: 80 },
      { name: 'DocxTool', calls: 2, ok: 1, failed: 1, successRatePct: 50, avgDurationMs: 20 }
    ],
    byAgent: [{ name: '引用核验', calls: 12, ok: 10, failed: 2, successRatePct: 83, avgDurationMs: 40 }]
  },
  llm: { calls: 9, tokens: 4000, promptTokens: 0, completionTokens: 0, structuredFail: 1, avgDurationMs: 850, durationMs: 7650, p50Ms: 800, p95Ms: 1200, tokenDeltaPct: 10 },
  citation: { lookupDoi: 12, lookupOk: 10, notVerified: 2, notVerifiedPct: 17, inventedDoiDropped: 1 },
  rag: { privateRetrievals: 4, publicRetrievals: 2, knn: 1, emptyHits: 2, privateEmptyHits: 1, publicEmptyHits: 0, knnEmptyHits: 1, hasDuration: true, privateAvgDurationMs: 25, publicAvgDurationMs: 30, knnAvgDurationMs: 12 },
  backlog: { pending: 1, running: 0, waitingAccept: 1, inProgress: 1, leasesHeld: 1, caption: '窗口内 PENDING / RUNNING / 待确认 / lease 持有。不是 MQ 管理面，不是 SLA。' },
  alerts: {
    caption: '窗口规则，不是 SLA、不是 pager。',
    items: [{ id: 'fencing', tone: 'danger', title: 'fencing 拒绝', count: 1, caption: '窗口规则，不是 SLA、不是 pager。' }]
  },
  crossTenant: { activeTenants: 12, failedTenants: 1, tasks: 3, caption: '窗口内全平台汇总，C 端不可见。不是 SLA。' }
}

describe('observability helpers', () => {
  it('normalizes time ranges used by the ops console', () => {
    expect(rangeOf('15m')).toBe('15m')
    expect(rangeOf('1h')).toBe('1h')
    expect(rangeOf('24h')).toBe('24h')
    expect(rangeOf('15d')).toBe('15d')
    expect(rangeOf('6m')).toBe('6m')
    expect(rangeOf('12m')).toBe('12m')
    expect(rangeOf('30d')).toBe('7d')
    expect(rangeOf('all')).toBe('all')
    expect(rangeOf('weird')).toBe('7d')
    expect(isMonthRange('6m')).toBe(true)
    expect(isMonthRange('all')).toBe(true)
    expect(isMonthRange('15d')).toBe(false)
    expect(sparseAxisTick(0, 15)).toBe(true)
    expect(sparseAxisTick(1, 15)).toBe(false)
    expect(sparseAxisTick(3, 15)).toBe(true)
    expect(sparseAxisTick(14, 15)).toBe(true)
    expect(TENANT_PAGE_SIZE).toBe(9)
    expect(TRACE_PAGE_SIZE).toBe(9)
    expect(filterTenants(DASHBOARD.tenants, '林的').map((r) => r.tenantName)).toEqual(['林的实验室'])
    const traces = recentDurationRows(DASHBOARD.recent)
    expect(filterTraces(traces, 'ZYT2').map((r) => r.taskId)).toEqual(['ZYT2'])
    expect(filterTraces(traces, '林的').map((r) => r.taskId)).toEqual(['ZYT2'])
    expect(filterTraces(traces, '没能完成').map((r) => r.taskId)).toEqual(['ZYT2'])
    expect(filterTraces(traces, '语言润色').map((r) => r.taskId)).toEqual(['ZYT2'])
  })

  it('builds KPI cards without SLA copy', () => {
    const cards = kpiCards(DASHBOARD)
    expect(cards.map((c) => c.label)).toEqual([
      '全局任务', '完成占比', '进行中', 'lease 持有', '失败数', 'checkpoint 跳过', 'token 合计'
    ])
    expect(cards[0].value).toBe('3')
    expect(cards[1].value).toBe('67%')
    expect(cards[0].spark.hasData).toBe(true)
    expect(sparkline([1, 2, 0]).line).toContain('M')
    expect(cards.every((c) => !/SLA 达标/.test(c.label))).toBe(true)
    expect(OBSERVABILITY_CAPTION).toContain('非 SLA')
  })

  it('turns failure codes and agent duration into bar rows', () => {
    const rows = errorCodeRows(DASHBOARD.errorCodes)
    expect(rows[0]).toMatchObject({ code: 'timeout', label: errorCodeLabel('timeout'), count: 1, share: 50 })
    expect(agentDurationRows(DASHBOARD.agents)[0].name).toBe('引用核验')
    expect(toolAxisLabel('AcademicSearch')).toBe('DOI 检索')
    expect(toolAxisLabel('AcademicSearchTool')).toBe('DOI 检索')
    expect(toolAxisLabel('DocxTool')).toBe('Docx')
    expect(toolAxisLabel('引用核验')).toBe('引用核验')
    expect(wrapAxisLabel('DOI 检索', 4)).toEqual(['DOI', '检索'])
    expect(wrapAxisLabel('引用核验', 4)).toEqual(['引用核验'])
    expect(axisTickLines('引用核验', { dense: true })).toEqual(['引用', '核验'])
    expect(axisTickLines('AcademicSearch', { dense: true })).toEqual(['DOI'])
    expect(axisTickLines('AcademicSearch', { dense: false, maxPerLine: 8 })).toEqual(['DOI 检索'])
    expect(axisTickLines('AcademicSearch', { dense: false })).toEqual(['DOI', '检索'])
    expect(chartFootnote('tool', ['AcademicSearch', 'ManuscriptRetrieval', 'DocumentRead', 'WebSearch', 'PDFParse', 'DocxTool', 'KnowledgeRetrieval', 'Diff'])).toBe(
      'DOI＝DOI / Crossref 查询（Java lookupDoi）；稿件＝稿件检索（ManuscriptRetrieval）；读稿＝读取用户稿件（DocumentRead）；网页＝WebSearch / 网页检索；PDF＝PDF 解析（PDFParse）；Docx＝Docx 处理；知识＝RAG / 知识检索（KnowledgeRetrieval）；Diff＝改稿 diff 只读对照。按工具计数，不是 Agent，不是 SLA。'
    )
    expect(chartFootnote('agent-tools', ['引用核验', '学术审稿', '语言润色', '图表检查', '修改执行', '结果复核'])).toBe(
      '横轴为 Agent。引用核验 Citation；学术审稿 Academic；语言润色 Style（不授予 AcademicSearch）；图表检查 Figure；修改执行 Execution；结果复核 Verification。柱高为该 Agent 窗口内工具次数，不是 SLA。'
    )
    const sevenAgents = ['引用核验', '图表检查', '学术审稿', '语言润色', '改稿计划', '修改执行', '结果复核']
    expect(chartFootnote('agent-duration', sevenAgents)).toBe(
      '横轴短名两行：引用／核验＝引用核验 Citation；图表／检查＝图表检查 Figure；学术／审稿＝学术审稿 Academic；语言／润色＝语言润色 Style（不授予 AcademicSearch）；改稿／计划＝改稿计划 Planning；修改／执行＝修改执行 Execution；结果／复核＝结果复核 Verification。柱高为窗口内平均 duration，不是 SLA。'
    )
    expect(chartFootnote('agent-token', sevenAgents)).toBe(
      '横轴短名两行：引用／核验＝引用核验 Citation；图表／检查＝图表检查 Figure；学术／审稿＝学术审稿 Academic；语言／润色＝语言润色 Style（不授予 AcademicSearch）；改稿／计划＝改稿计划 Planning；修改／执行＝修改执行 Execution；结果／复核＝结果复核 Verification。柱高为窗口内 Agent span token 合计，不是 SLA。'
    )
    expect(chartFootnote('agent-tools', sevenAgents)).not.toContain('结果复核 Reviewer')
    expect(chartFootnote('tool', [])).toContain('lookupDoi')
    expect(trendChart(DASHBOARD.trend).hasData).toBe(true)
    expect(trendChart(DASHBOARD.trend).failedLine).toContain(',')
    const recent = recentDurationRows(DASHBOARD.recent)
    expect(recent[1].taskId).toBe('ZYT2')
    expect(recent[1].errorLabel).toBe('超时')
    expect(durationKpi(850)).toBe('850 ms')
    expect(durationKpi(null)).toBe('—')
    expect(durationKpi('')).toBe('—')
    expect(tokenDeltaText(10)).toContain('不是费用 SLA')
    expect(tokenDeltaText(null)).toContain('—')
    expect(alertRows(DASHBOARD.alerts)[0].id).toBe('fencing')
    expect(toolAxisLabel('WebSearch')).toBe('网页检索')
    expect(toolAxisLabel('DocxTool')).toBe('Docx')
    expect(axisTickLines('WebSearch', { dense: true })).toEqual(['网页'])
  })
})

describe('review task panels', () => {
  it('defaults the review page to 需你确认 and pages long lists by 5', () => {
    expect(taskPanelOf('')).toBe('confirm')
    expect(taskPanelOf('trace')).toBe('confirm')
    expect(taskPanelOf('overview')).toBe('overview')
    const sliced = pageSlice(['a', 'b', 'c', 'd', 'e', 'f'], 1, 5)
    expect(sliced.items).toHaveLength(5)
    expect(sliced.total).toBe(6)
    expect(pageSlice(['a', 'b', 'c', 'd', 'e', 'f'], 2, 5).items).toEqual(['f'])
  })
})

describe('ops console', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockImplementation((path: unknown) => {
      const p = String(path)
      if (p === '/me') return Promise.resolve({ operator: true, ops: true, email: 'demo@zhiyun.dev' })
      if (p.startsWith('/ops/observability') || p.startsWith('/observability')) return Promise.resolve(DASHBOARD)
      if (p.startsWith('/reviews/ZYT2/trace')) {
        return Promise.resolve({
          taskId: 'ZYT2',
          nodes: [{
            agent: 'CITATION_INTEGRITY',
            name: '引用核验',
            status: 'DONE',
            durationMs: 1200,
            tokens: 2000,
            startedAt: '2026-09-11T00:00:00.000Z',
            errorCode: 'timeout',
            toolName: 'AcademicSearchTool',
            toolCalls: [{ name: 'AcademicSearchTool' }, { name: 'lookupDoi' }]
          }]
        })
      }
      return Promise.resolve({})
    })
  })

  it('renders Grafana-style KPIs and opens a waterfall on the ops console', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/ops', component: OpsConsole },
        { path: '/', component: Blank }
      ]
    })
    await router.push('/ops')
    await router.isReady()
    const wrapper = mount(OpsConsole, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="ops-console"]').exists()).toBe(true)
    const board = wrapper.get('[data-testid="obs-board"]')
    expect(board.classes()).toContain('ops-dense')
    expect(board.classes()).toContain('ops-fill')
    expect(wrapper.find('[data-testid="ops-kpi"]').exists()).toBe(true)
    expect(wrapper.findAll('.ops-stat')).toHaveLength(7)
    const text = board.text()
    expect(text).toContain('智云观测')
    expect(text).toContain('运行观测')
    expect(text).toContain('非 SLA')
    expect(text).toContain('15m')
    expect(text).toContain('1h')
    expect(text).toContain('近半月')
    expect(text).toContain('近半年')
    expect(text).toContain('近一年')
    expect(text).not.toContain('一个月')
    expect(text).toContain('至今')
    expect(text).toContain('Tool calls')
    expect(text).toContain('按工具计数')
    expect(text).not.toContain('含种子演示')
    expect(text).not.toContain('种子')
    expect(text).toContain('DOI 检索')
    expect(wrapper.get('[data-testid="ops-tools"]').find('svg').attributes('overflow')).toBe('hidden')
    expect(wrapper.get('[data-testid="ops-runtime"]').classes()).toContain('ops-quad')
    expect(wrapper.get('[data-testid="ops-llm"]').find('.ops-mini-kpis--8').exists()).toBe(true)
    expect(wrapper.get('[data-testid="ops-citation"]').find('.ops-mini-kpis--4').exists()).toBe(true)
    expect(wrapper.get('[data-testid="ops-harness"]').find('.ops-mini-kpis--4').exists()).toBe(true)
    expect(wrapper.get('[data-testid="ops-llm"]').findAll('.ops-mini-kpi')).toHaveLength(8)
    expect(wrapper.get('[data-testid="ops-citation"]').findAll('.ops-mini-kpi')).toHaveLength(4)
    expect(wrapper.get('[data-testid="ops-harness"]').findAll('.ops-mini-kpi')).toHaveLength(4)
    expect(wrapper.get('[data-testid="ops-llm"]').findAll('.ops-mini-kpi__body')).toHaveLength(8)
    expect(wrapper.get('[data-testid="ops-llm"]').text()).toContain('窗口耗时')
    expect(wrapper.get('[data-testid="ops-llm"]').text()).toContain('850 ms')
    expect(wrapper.get('[data-testid="ops-llm"]').text()).toContain('P50')
    expect(wrapper.get('[data-testid="ops-llm"]').text()).toContain('P95')
    expect(wrapper.get('[data-testid="ops-llm"]').text()).toContain('无 TTFT')
    expect(wrapper.get('[data-testid="ops-tool-fail"]').text()).toContain('工具失败')
    expect(wrapper.get('[data-testid="ops-tool-fail"]').text()).toContain('节点失败')
    expect(wrapper.get('[data-testid="ops-rag-empty"]').text()).toContain('空召回')
    expect(wrapper.get('[data-testid="ops-rag"]').text()).toContain('不是召回率')
    expect(wrapper.get('[data-testid="ops-rag"]').text()).toContain('运行观测')
    expect(wrapper.get('[data-testid="ops-rag-duration"]').text()).toContain('private 耗时')
    expect(wrapper.get('[data-testid="ops-alerts"]').text()).toContain('窗口规则')
    expect(wrapper.get('[data-testid="ops-alerts"]').text()).toContain('不是 pager')
    expect(wrapper.get('[data-testid="ops-alert-fencing"]').text()).toContain('fencing 拒绝')
    expect(wrapper.get('[data-testid="ops-duty"]').text()).toContain('PENDING')
    expect(wrapper.get('[data-testid="ops-duty"]').text()).toContain('活跃租户')
    expect(wrapper.get('[data-testid="ops-duty"]').text()).toContain('失败租户')
    expect(wrapper.get('[data-testid="ops-duty"]').text()).toContain('P95')
    expect(wrapper.get('[data-testid="ops-tools"]').text()).toContain('网页')
    expect(wrapper.get('[data-testid="ops-tools"]').text()).toContain('Docx')
    expect(text).not.toContain('Recall@5')
    expect(text).not.toContain('Prometheus')
    expect(text).not.toContain('Grafana')
    expect(text).not.toContain('meters')
    expect(wrapper.get('[data-testid="ops-tools-note"]').text()).toContain('DOI / Crossref 查询（Java lookupDoi）')
    expect(wrapper.get('[data-testid="ops-agent-tools-note"]').text()).toContain('引用核验 Citation')
    expect(wrapper.get('[data-testid="ops-agent-duration-note"]').text()).toContain('平均 duration')
    expect(wrapper.get('[data-testid="ops-agent-tokens-note"]').text()).toContain('token 合计')
    expect(wrapper.get('[data-testid="ops-tools-note"]').text()).not.toContain('SLA 达标')
    expect(text).toContain('lookupDoi')
    expect(text).toContain('全局任务')
    expect(text).toContain('完成占比')
    expect(text).toContain('lease 持有')
    expect(text).toContain('token 合计')
    expect(text).toContain('失败码')
    expect(text).toContain('按 Agent 成功率')
    expect(text).toContain('任务完成')
    expect(text).toContain('全部汇总')
    expect(text).toContain('演示实验室')
    expect(text).toContain('林的实验室')
    expect(text).toContain('返回登录')
    expect(text).not.toContain('返回审校')
    expect(text).not.toContain('SLA 达标')
    expect(text).not.toContain('Recall@5')
    expect(api).toHaveBeenCalledWith('/ops/observability?range=7d')
    expect(wrapper.find('[data-testid="ops-tools"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ops-tenant-q"]').exists()).toBe(true)
    await wrapper.get('[data-testid="ops-range-15d"]').trigger('click')
    await flushPromises()
    expect(api.mock.calls.some((call) => String(call[0]).includes('range=15d'))).toBe(true)
    await wrapper.get('[data-testid="ops-tenant-q"]').setValue('林的')
    await flushPromises()
    expect(wrapper.find('[data-testid="ops-tenant-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ops-tenant-10"]').exists()).toBe(false)
    await wrapper.get('[data-testid="ops-tenant-2"]').trigger('click')
    await flushPromises()
    expect(api.mock.calls.some((call) => String(call[0]).includes('tenantId=2'))).toBe(true)
    expect(wrapper.find('[data-testid="ops-fail-trace"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="ops-fail-trace"]').classes()).toContain('ops-split--align')
    expect(wrapper.find('[data-testid="ops-trace-q"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ops-trace-ZYT21"]').exists()).toBe(false)
    await wrapper.get('[data-testid="ops-trace-q"]').setValue('ZYT12')
    await flushPromises()
    expect(wrapper.find('[data-testid="ops-trace-ZYT12"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ops-trace-ZYT1"]').exists()).toBe(false)
    await wrapper.get('[data-testid="ops-trace-q"]').setValue('林的')
    await flushPromises()
    expect(wrapper.find('[data-testid="ops-trace-ZYT2"]').exists()).toBe(true)
    await wrapper.get('[data-testid="ops-trace-ZYT2"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="ops-trace-ZYT2"]').classes()).toContain('on')
    expect(wrapper.find('[data-testid="trace-waterfall"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="ops-waterfall"]').text()).toContain('引用核验')
    await wrapper.get('[data-testid="ops-span-CITATION_INTEGRITY"]').trigger('mouseenter')
    expect(wrapper.get('[data-testid="ops-span-tip"]').text()).toContain('Agent')
    expect(wrapper.get('[data-testid="ops-span-tip"]').text()).toContain('耗时')
    expect(wrapper.get('[data-testid="ops-span-tip"]').text()).toContain('token')
    expect(wrapper.get('[data-testid="ops-span-tip"]').text()).toContain('tool')
    expect(wrapper.get('[data-testid="ops-span-tip"]').text()).toContain('errorCode')
    expect(wrapper.get('[data-testid="ops-span-tip"]').text()).toContain('状态')
    expect(wrapper.get('[data-testid="ops-span-tip"]').text()).toContain('引用核验')
    await wrapper.get('[data-testid="ops-span-CITATION_INTEGRITY"]').trigger('click')
    expect(wrapper.get('[data-testid="ops-span-detail"]').text()).toContain('errorCode')
    expect(wrapper.get('[data-testid="ops-span-detail"]').text()).toContain('timeout')
    wrapper.unmount()
  })

  it('sends regular users to ops login instead of showing the console', async () => {
    api.mockImplementation((path: unknown) => {
      if (String(path) === '/me') return Promise.resolve({ operator: false, ops: false, email: 'lin@zhiyun.dev' })
      return Promise.resolve({})
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/ops', component: OpsConsole },
        { path: '/ops/login', component: Blank },
        { path: '/', component: Blank }
      ]
    })
    await router.push('/ops')
    await router.isReady()
    const wrapper = mount(OpsConsole, { global: { plugins: [router] } })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/ops/login')
    expect(wrapper.find('[data-testid="obs-board"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps a C-end operator JWT off the all-tenant board', async () => {
    api.mockImplementation((path: unknown) => {
      if (String(path) === '/me') return Promise.resolve({ operator: true, ops: false, email: 'demo@zhiyun.dev' })
      return Promise.resolve({})
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/ops', component: OpsConsole },
        { path: '/ops/login', component: Blank },
        { path: '/', component: Blank }
      ]
    })
    await router.push('/ops')
    await router.isReady()
    const wrapper = mount(OpsConsole, { global: { plugins: [router] } })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/ops/login')
    expect(wrapper.find('[data-testid="obs-board"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('OpsBarChart axis labels stay inside the chart', () => {
  it('maps AcademicSearch to a short axis name and clips overflow', () => {
    const wrapper = mount(OpsBarChart, {
      props: { labels: ['AcademicSearch'], values: [12] }
    })
    expect(wrapper.find('.x-tick').text()).toContain('DOI 检索')
    expect(wrapper.find('.x-tick title').text()).toBe('AcademicSearch')
    expect(wrapper.get('svg').attributes('overflow')).toBe('hidden')
    expect(wrapper.find('.chart-wrap').exists()).toBe(true)
    wrapper.unmount()
  })

  it('keeps leftover long names horizontal and wraps to two lines', () => {
    const wrapper = mount(OpsBarChart, {
      props: {
        labels: ['SuperLongToolName'],
        values: [4]
      }
    })
    const tick = wrapper.find('.x-tick')
    expect(tick.attributes('transform') || '').not.toContain('rotate')
    expect(wrapper.html()).not.toMatch(/rotate\(-40/)
    expect(tick.findAll('tspan').length).toBeGreaterThanOrEqual(2)
    expect(tick.text()).toMatch(/…/)
    expect(wrapper.get('svg').attributes('overflow')).toBe('hidden')
    wrapper.unmount()
  })

  it('keeps seven agent bars horizontal without tilt', () => {
    const labels = ['引用核验', '图表检查', '学术审稿', '语言润色', '改稿计划', '修改执行', '结果复核']
    const wrapper = mount(OpsBarChart, {
      props: { labels, values: labels.map((_, i) => i + 1) }
    })
    const ticks = wrapper.findAll('.x-tick')
    expect(ticks).toHaveLength(7)
    ticks.forEach((tick) => {
      expect(tick.attributes('transform') || '').not.toContain('rotate')
      expect(tick.classes()).toContain('tight')
      expect(tick.findAll('tspan').length).toBe(2)
    })
    expect(wrapper.html()).not.toMatch(/rotate\(-40/)
    expect(wrapper.get('svg').attributes('overflow')).toBe('hidden')
    wrapper.unmount()
  })
})

describe('History stays a review list', () => {
  it('does not switch into an observability view', async () => {
    api.mockReset()
    api.mockImplementation((path: unknown) => {
      const p = String(path)
      if (p.startsWith('/reviews')) {
        return Promise.resolve({ items: [], total: 0, page: 1, size: 5 })
      }
      if (p.startsWith('/inbox')) return Promise.resolve({ items: [], unreadCount: 0 })
      return Promise.resolve({})
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/history', component: History }]
    })
    await router.push('/history?view=observability')
    await router.isReady()
    const wrapper = mount(History, {
      global: {
        plugins: [router],
        provide: { refreshMe: vi.fn().mockResolvedValue(undefined) }
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('审校记录')
    expect(wrapper.text()).not.toContain('运行观测')
    expect(wrapper.find('[data-testid="obs-open"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="obs-board"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
