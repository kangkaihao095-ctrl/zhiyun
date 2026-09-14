import { errorCodeLabel } from './public-error'
import { AGENT_NAME, WORKFLOW_AGENTS, formatDurationMs } from './review.js'
import { statusLabel } from './labels.js'

export const OBSERVABILITY_CAPTION = '运行观测，非 SLA。评估集 Recall 与要点命中不在此页。'

export const OBS_RANGES = [
  { key: '15m', label: '15m' },
  { key: '1h', label: '1h' },
  { key: '24h', label: '24h' },
  { key: '7d', label: '7d' },
  { key: '15d', label: '近半月' },
  { key: '6m', label: '近半年' },
  { key: '12m', label: '近一年' },
  { key: 'all', label: '至今' }
]

export const TENANT_PAGE_SIZE = 9
export const TRACE_PAGE_SIZE = 9

export function rangeOf(raw) {
  const key = String(raw || '7d')
  return OBS_RANGES.some((r) => r.key === key) ? key : '7d'
}

export function rangeLabelOf(raw) {
  const key = rangeOf(raw)
  return OBS_RANGES.find((r) => r.key === key)?.label || key
}

export function isMonthRange(raw) {
  const key = rangeOf(raw)
  return key === '6m' || key === '12m' || key === 'all'
}

/** 长区间抽稀横轴：近半月按天但隔几天标一个，月聚合也避免 12 个 YYYY-MM 挤叠。 */
export function sparseAxisTick(index, total) {
  const i = Number(index) || 0
  const n = Number(total) || 0
  if (n <= 8) return true
  const step = Math.max(2, Math.ceil(n / 6))
  return i === 0 || i === n - 1 || i % step === 0
}

function niceMax(n) {
  const v = Number(n) || 0
  if (v <= 0) return 1
  const exp = 10 ** Math.floor(Math.log10(v))
  const f = v / exp
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10
  return nice * exp
}

export function sparkline(values, w = 88, h = 28) {
  const nums = (Array.isArray(values) ? values : []).map((v) => Number(v) || 0)
  if (!nums.length) {
    return { width: w, height: h, line: '', area: '', hasData: false }
  }
  const max = Math.max(...nums, 1)
  const n = Math.max(nums.length - 1, 1)
  const pts = nums.map((v, i) => {
    const x = nums.length === 1 ? w / 2 : (i / n) * w
    const y = h - 2 - (v / max) * (h - 4)
    return [x, y]
  })
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ')
  const last = pts[pts.length - 1]
  const area = `${line} L${last[0].toFixed(1)},${h} L${pts[0][0].toFixed(1)},${h} Z`
  return {
    width: w,
    height: h,
    line,
    area,
    hasData: nums.some((v) => v > 0)
  }
}

export function kpiCards(data) {
  const kpis = data?.kpis || {}
  const tasks = data?.tasks || {}
  const harness = data?.harness || {}
  const trend = Array.isArray(data?.trend) ? data.trend : []
  const started = Number(kpis.tasks ?? tasks.started ?? 0)
  const succeeded = Number(tasks.succeeded || 0) + Number(tasks.waitingAccept || 0)
  const inProgress = Number(kpis.inProgress ?? ((tasks.pending || 0) + (tasks.running || 0)))
  const failed = Number(kpis.failed ?? tasks.failed ?? 0)
  const rate = kpis.successRatePct != null
    ? Number(kpis.successRatePct)
    : (started <= 0 ? 0 : Math.round((succeeded * 100) / started))
  const startedSeries = trend.map((row) => Number(row?.started || 0))
  const failedSeries = trend.map((row) => Number(row?.failed || 0))
  const rateSeries = trend.map((row) => {
    const s = Number(row?.started || 0)
    const ok = Number(row?.succeeded || 0)
    return s <= 0 ? 0 : Math.round((ok * 100) / s)
  })
  return [
    {
      key: 'tasks',
      value: String(started),
      label: data?.scope === 'tenant' ? '本租户任务' : '全局任务',
      hint: data?.scope === 'tenant' ? '该实验室窗口内创建，不是 SLA' : '全部实验室窗口内创建，不是 SLA',
      unit: '次',
      spark: sparkline(startedSeries)
    },
    {
      key: 'rate',
      value: rate + '%',
      label: '完成占比',
      hint: '已完成 + 待确认 / 任务数',
      unit: '%',
      spark: sparkline(rateSeries)
    },
    {
      key: 'progress',
      value: String(inProgress),
      label: '进行中',
      hint: 'PENDING + RUNNING',
      unit: '个',
      spark: sparkline(startedSeries.map((_, i) => Math.max(0, Number(trend[i]?.started || 0) - Number(trend[i]?.succeeded || 0) - Number(trend[i]?.failed || 0))))
    },
    {
      key: 'lease',
      value: String(kpis.leasesHeld ?? harness.leasesHeld ?? 0),
      label: 'lease 持有',
      hint: '未过期执行租约',
      unit: '个',
      spark: sparkline([])
    },
    {
      key: 'failed',
      value: String(failed),
      label: '失败数',
      hint: '窗口内 FAILED',
      unit: '次',
      spark: sparkline(failedSeries)
    },
    {
      key: 'skipped',
      value: String(kpis.checkpointSkipped ?? harness.checkpointSkipped ?? 0),
      label: 'checkpoint 跳过',
      hint: '从检查点续跑跳过的节点',
      unit: '次',
      spark: sparkline([])
    },
    {
      key: 'tokens',
      value: String(kpis.tokens ?? data?.tokens ?? 0),
      label: 'token 合计',
      hint: '窗口内 Agent span',
      unit: 'tok',
      spark: sparkline((Array.isArray(data?.agents) ? data.agents : []).map((row) => Number(row?.tokens || 0)))
    }
  ]
}

export function errorCodeRows(codes) {
  const list = Array.isArray(codes) ? codes : []
  const total = list.reduce((sum, row) => sum + Number(row?.count || 0), 0)
  return list.map((row) => {
    const count = Number(row?.count || 0)
    return {
      code: String(row?.code || 'unknown'),
      label: errorCodeLabel(row?.code),
      count,
      share: total <= 0 ? 0 : Math.round((count * 100) / total)
    }
  })
}

export function agentDurationRows(agents) {
  const list = Array.isArray(agents) ? agents : []
  const max = list.reduce((m, row) => Math.max(m, Number(row?.avgDurationMs || 0)), 0)
  return list.map((row) => ({
    agent: String(row?.agent || ''),
    name: String(row?.name || row?.agent || ''),
    avgDurationMs: Number(row?.avgDurationMs || 0),
    durationText: formatDurationMs(row?.avgDurationMs) || '0 ms',
    runs: Number(row?.runs || 0),
    failed: Number(row?.failed || 0),
    tokens: Number(row?.tokens || 0),
    share: max <= 0 ? 0 : Math.round((Number(row?.avgDurationMs || 0) * 100) / max)
  }))
}

function linePoints(rows, key, x, y) {
  return rows.map((row, i) => `${x(i).toFixed(1)},${y(row?.[key] || 0).toFixed(1)}`).join(' ')
}

function areaPath(rows, key, x, y, baseline) {
  if (!rows.length) return ''
  const line = rows.map((row, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(row?.[key] || 0).toFixed(1)}`).join(' ')
  const lastX = x(rows.length - 1).toFixed(1)
  const firstX = x(0).toFixed(1)
  return `${line} L${lastX},${baseline.toFixed(1)} L${firstX},${baseline.toFixed(1)} Z`
}

export function trendChart(trend) {
  const rows = Array.isArray(trend) ? trend : []
  const w = 720
  const h = 228
  const padL = 44
  const padR = 14
  const padT = 14
  const padB = 32
  const innerW = w - padL - padR
  const innerH = h - padT - padB
  const rawMax = rows.reduce((m, row) => Math.max(
    m,
    Number(row?.started || 0),
    Number(row?.succeeded || 0),
    Number(row?.failed || 0)
  ), 0)
  const max = niceMax(rawMax)
  const n = Math.max(rows.length - 1, 1)
  const x = (i) => padL + (rows.length <= 1 ? innerW / 2 : (i / n) * innerW)
  const y = (v) => padT + innerH - (max <= 0 ? 0 : (Number(v) / max) * innerH)
  const baseline = padT + innerH
  const yTicks = [0, max / 2, max].map((val) => ({
    value: val,
    label: String(Math.round(val)),
    y: y(val)
  }))
  const xTicks = rows.filter((_, i) => i === 0 || i === rows.length - 1 || i === Math.floor(rows.length / 2))
    .map((row) => ({
      label: String(row.bucket || ''),
      x: x(rows.indexOf(row))
    }))
  const grid = yTicks.map((tick) => ({
    y: tick.y,
    x1: padL,
    x2: w - padR
  }))
  const bars = rows.map((row, i) => {
    const bw = Math.max(2, innerW / Math.max(rows.length, 1) * 0.28)
    const val = Number(row?.started || 0)
    const bh = max <= 0 ? 0 : (val / max) * innerH
    return {
      x: x(i) - bw / 2,
      y: padT + innerH - bh,
      w: bw,
      h: bh,
      label: String(row?.bucket || '')
    }
  })
  return {
    width: w,
    height: h,
    max,
    padL,
    padB: h - 10,
    startedLine: linePoints(rows, 'started', x, y),
    succeededLine: linePoints(rows, 'succeeded', x, y),
    failedLine: linePoints(rows, 'failed', x, y),
    startedArea: areaPath(rows, 'started', x, y, baseline),
    succeededArea: areaPath(rows, 'succeeded', x, y, baseline),
    failedArea: areaPath(rows, 'failed', x, y, baseline),
    bars,
    ticks: xTicks,
    yTicks,
    grid,
    hasData: rows.some((row) => Number(row?.started || 0) > 0 || Number(row?.succeeded || 0) > 0 || Number(row?.failed || 0) > 0)
  }
}

export function vbarChart(rows, valueKey, textKey) {
  const list = Array.isArray(rows) ? rows : []
  const w = 560
  const h = 200
  const padL = 52
  const padR = 12
  const padT = 12
  const padB = 36
  const innerW = w - padL - padR
  const innerH = h - padT - padB
  const rawMax = list.reduce((m, row) => Math.max(m, Number(row?.[valueKey] || 0)), 0)
  const max = niceMax(rawMax)
  const n = list.length
  const slot = innerW / Math.max(n, 1)
  const bw = Math.min(36, Math.max(8, slot * 0.55))
  const y = (v) => padT + innerH - (max <= 0 ? 0 : (Number(v) / max) * innerH)
  const yTicks = [0, max / 2, max].map((val) => ({
    value: val,
    label: valueKey === 'avgDurationMs' ? (formatDurationMs(val) || '0') : String(Math.round(val)),
    y: y(val)
  }))
  const bars = list.map((row, i) => {
    const val = Number(row?.[valueKey] || 0)
    const bh = max <= 0 ? 0 : (val / max) * innerH
    return {
      x: padL + (i + 0.5) * slot - bw / 2,
      y: padT + innerH - bh,
      w: bw,
      h: Math.max(0, bh),
      cx: padL + (i + 0.5) * slot,
      label: String(row.name || row.label || row.agent || ''),
      valueText: textKey && row[textKey] != null ? String(row[textKey]) : String(val)
    }
  })
  return {
    width: w,
    height: h,
    max,
    padL,
    baseline: padT + innerH,
    yTicks,
    grid: yTicks.map((tick) => ({ y: tick.y, x1: padL, x2: w - padR })),
    bars,
    hasData: list.some((row) => Number(row?.[valueKey] || 0) > 0)
  }
}

export function filterTenants(tenants, q) {
  const list = tenantRows(tenants)
  const needle = String(q || '').trim().toLowerCase()
  if (!needle) return list
  return list.filter((row) => row.tenantName.toLowerCase().includes(needle)
    || String(row.tenantId).includes(needle))
}

export function filterTraces(rows, q) {
  const list = Array.isArray(rows) ? rows : []
  const needle = String(q || '').trim().toLowerCase()
  if (!needle) return list
  return list.filter((row) => traceFields(row).some((field) => fieldMatch(field, needle)))
}

function fieldMatch(value, needle) {
  const v = String(value || '').toLowerCase()
  if (!v) return false
  if (v === needle) return true
  const idx = v.indexOf(needle)
  if (idx < 0) return false
  const after = v[idx + needle.length]
  if (after && /\d/.test(after) && /\d$/.test(needle)) return false
  return true
}

function traceFields(row) {
  const workflow = String(row?.workflow || '')
  const agents = WORKFLOW_AGENTS[workflow] || []
  const checkpoint = String(row?.checkpointAgent || '')
  return [
    row?.taskId,
    row?.tenantName,
    row?.tenantId,
    row?.status,
    row?.statusLabel,
    row?.workflowName,
    workflow,
    checkpoint,
    AGENT_NAME[checkpoint],
    ...agents,
    ...agents.map((id) => AGENT_NAME[id] || id),
    row?.agentNames
  ]
}

const TOOL_AXIS_SHORT = {
  AcademicSearch: 'DOI 检索',
  AcademicSearchTool: 'DOI 检索',
  WebSearch: '网页检索',
  WebSearchTool: '网页检索',
  CitationParser: '引用解析',
  MetadataVerifier: '元数据核验',
  ManuscriptRetrieval: '稿件检索',
  KnowledgeRetrieval: '知识检索',
  FigureExtract: '抽图',
  FigureMetadata: '图表元数据',
  PDFParse: 'PDF 解析',
  PDFRender: 'PDF 渲染',
  DocumentRead: '读稿',
  DocumentPatch: '改稿',
  DocxTool: 'Docx',
  Diff: 'Diff',
  Vision: '视觉'
}

/** 柱多时再压一档，避免横轴倾斜。 */
const AXIS_DENSE = {
  'DOI 检索': 'DOI',
  '网页检索': '网页',
  '稿件检索': '稿件',
  '知识检索': '知识',
  'PDF 解析': 'PDF',
  'PDF 渲染': '渲染',
  '图表元数据': '图表',
  '元数据核验': '元数据',
  '引用解析': '引用'
}

/** 横轴短名 → 脚注全称。与 TOOL_AXIS_SHORT / AXIS_DENSE、Agent 中文名对齐。 */
const TOOL_FOOTNOTE = {
  AcademicSearch: ['DOI', 'DOI / Crossref 查询（Java lookupDoi）'],
  AcademicSearchTool: ['DOI', 'DOI / Crossref 查询（Java lookupDoi）'],
  ManuscriptRetrieval: ['稿件', '稿件检索（ManuscriptRetrieval）'],
  DocumentRead: ['读稿', '读取用户稿件（DocumentRead）'],
  WebSearch: ['网页', 'WebSearch / 网页检索'],
  WebSearchTool: ['网页', 'WebSearch / 网页检索'],
  PDFParse: ['PDF', 'PDF 解析（PDFParse）'],
  PDFRender: ['渲染', 'PDF 渲染（PDFRender）'],
  DocxTool: ['Docx', 'Docx 处理'],
  KnowledgeRetrieval: ['知识', 'RAG / 知识检索（KnowledgeRetrieval）'],
  Diff: ['Diff', '改稿 diff 只读对照'],
  DocumentPatch: ['改稿', 'DocumentPatch 写入候选稿'],
  CitationParser: ['引用', '引用解析（CitationParser）'],
  MetadataVerifier: ['元数据', '元数据核验（MetadataVerifier）'],
  FigureExtract: ['抽图', '图表抽取（FigureExtract）'],
  FigureMetadata: ['图表', '图表元数据（FigureMetadata）'],
  Vision: ['视觉', '看图（Vision）'],
  kNN: ['kNN', 'RAG kNN 检索']
}

const AGENT_FOOTNOTE = {
  引用核验: ['引用／核验', '引用核验 Citation'],
  图表检查: ['图表／检查', '图表检查 Figure'],
  学术审稿: ['学术／审稿', '学术审稿 Academic'],
  语言润色: ['语言／润色', '语言润色 Style（不授予 AcademicSearch）'],
  改稿计划: ['改稿／计划', '改稿计划 Planning'],
  修改执行: ['修改／执行', '修改执行 Execution'],
  结果复核: ['结果／复核', '结果复核 Verification']
}

const DEFAULT_TOOL_NOTE_NAMES = [
  'AcademicSearch',
  'ManuscriptRetrieval',
  'DocumentRead',
  'WebSearch',
  'PDFParse',
  'DocxTool',
  'KnowledgeRetrieval',
  'Diff'
]

const DEFAULT_AGENT_NOTE_NAMES = Object.keys(AGENT_FOOTNOTE)

function uniqueNames(names, fallback) {
  const list = (Array.isArray(names) ? names : []).map((n) => String(n || '').trim()).filter(Boolean)
  if (list.length) return [...new Set(list)]
  return fallback
}

function toolFootnoteParts(names) {
  const seen = new Set()
  const parts = []
  for (const name of uniqueNames(names, DEFAULT_TOOL_NOTE_NAMES)) {
    const row = TOOL_FOOTNOTE[name]
    if (row) {
      if (seen.has(row[0])) continue
      seen.add(row[0])
      parts.push(`${row[0]}＝${row[1]}`)
      continue
    }
    const mapped = toolAxisLabel(name)
    const short = AXIS_DENSE[mapped] || mapped || name
    if (seen.has(short)) continue
    seen.add(short)
    parts.push(short === name ? name : `${short}＝${name}`)
  }
  return parts
}

function agentFootnoteParts(names, dense) {
  return uniqueNames(names, DEFAULT_AGENT_NOTE_NAMES).map((name) => {
    const row = AGENT_FOOTNOTE[name]
    if (!row) return name
    return dense ? `${row[0]}＝${row[1]}` : row[1]
  })
}

/** 各图下方短名说明。kind: tool | agent-tools | agent-duration | agent-token */
export function chartFootnote(kind, names) {
  if (kind === 'tool') {
    return `${toolFootnoteParts(names).join('；')}。按工具计数，不是 Agent，不是 SLA。`
  }
  const list = uniqueNames(names, DEFAULT_AGENT_NOTE_NAMES)
  const dense = list.length > 6
  const body = agentFootnoteParts(list, dense).join('；')
  const lead = dense ? '横轴短名两行：' : '横轴为 Agent。'
  if (kind === 'agent-duration') {
    return `${lead}${body}。柱高为窗口内平均 duration，不是 SLA。`
  }
  if (kind === 'agent-token') {
    return `${lead}${body}。柱高为窗口内 Agent span token 合计，不是 SLA。`
  }
  return `${lead}${body}。柱高为该 Agent 窗口内工具次数，不是 SLA。`
}

export function toolAxisLabel(name) {
  const raw = String(name || '').trim()
  if (!raw) return ''
  return TOOL_AXIS_SHORT[raw] || raw
}

function clipAxisLine(text, max) {
  const s = String(text || '')
  const n = Math.max(1, Number(max) || 1)
  if (s.length <= n) return s
  return `${s.slice(0, Math.max(1, n - 1))}…`
}

function hasCjk(text) {
  return /[\u3400-\u9fff]/.test(String(text || ''))
}

/** 横轴最多两行，禁止旋转。 */
export function wrapAxisLabel(text, maxPerLine = 4) {
  const s = String(text || '').trim()
  const max = Math.max(1, Number(maxPerLine) || 4)
  if (!s) return ['']
  if (s.length <= max) return [s]
  const space = s.indexOf(' ')
  if (space > 0 && space <= max) {
    return [s.slice(0, space), clipAxisLine(s.slice(space + 1), max)].filter(Boolean).slice(0, 2)
  }
  const camel = s.match(/^([A-Z]?[a-z]+|[A-Z]+(?![a-z]))([A-Z].+)$/)
  if (camel) {
    return [clipAxisLine(camel[1], max), clipAxisLine(camel[2], max)]
  }
  if (hasCjk(s)) {
    const mid = Math.min(max, Math.ceil(s.length / 2))
    return [s.slice(0, mid), clipAxisLine(s.slice(mid), max)]
  }
  return [s.slice(0, max), clipAxisLine(s.slice(max), max)]
}

export function axisTickLines(name, { maxPerLine = 4, dense = false } = {}) {
  const mapped = toolAxisLabel(name)
  const text = dense ? (AXIS_DENSE[mapped] || mapped) : mapped
  if (dense && hasCjk(text) && text.length === 4) {
    return [text.slice(0, 2), text.slice(2)]
  }
  return wrapAxisLabel(text, maxPerLine)
}

export function toolCallRows(rows) {
  return (Array.isArray(rows) ? rows : []).map((row) => ({
    name: String(row?.name || row?.tool || ''),
    calls: Number(row?.calls || 0),
    ok: Number(row?.ok || 0),
    failed: Number(row?.failed || 0),
    successRatePct: Number(row?.successRatePct || 0),
    avgDurationMs: Number(row?.avgDurationMs || 0)
  }))
}

export function tenantRows(tenants) {
  return (Array.isArray(tenants) ? tenants : []).map((row) => ({
    tenantId: Number(row?.tenantId || 0),
    tenantName: String(row?.tenantName || ('实验室 ' + (row?.tenantId || ''))),
    tasks: Number(row?.tasks || 0),
    sharePct: Number(row?.sharePct || 0),
    successRatePct: Number(row?.successRatePct || 0),
    failed: Number(row?.failed || 0),
    inProgress: Number(row?.inProgress || 0),
    leasesHeld: Number(row?.leasesHeld || 0),
    tokens: Number(row?.tokens || 0),
    errorCodes: Array.isArray(row?.errorCodes) ? row.errorCodes : []
  }))
}

export function recentDurationRows(recent) {
  return (Array.isArray(recent) ? recent : []).map((row) => ({
    taskId: String(row?.taskId || ''),
    tenantId: row?.tenantId,
    tenantName: String(row?.tenantName || ''),
    workflow: String(row?.workflow || ''),
    workflowName: String(row?.workflowName || row?.workflow || ''),
    status: String(row?.status || ''),
    statusLabel: statusLabel(row?.status),
    durationMs: Number(row?.durationMs || 0),
    durationText: formatDurationMs(row?.durationMs) || '—',
    tokens: Number(row?.tokens || 0),
    errorCode: String(row?.errorCode || ''),
    errorLabel: row?.errorCode ? errorCodeLabel(row.errorCode) : '',
    fencingToken: row?.fencingToken,
    checkpointAgent: row?.checkpointAgent || '',
    skipped: Number(row?.skipped || 0),
    lease: row?.lease || null
  }))
}

export function liveLeaseRows(leases) {
  return (Array.isArray(leases) ? leases : []).map((row) => ({
    taskId: String(row?.taskId || ''),
    status: String(row?.status || ''),
    statusLabel: statusLabel(row?.status),
    owner: String(row?.owner || ''),
    expireAt: row?.expireAt || '',
    fencingToken: row?.fencingToken
  }))
}

export function spanState(status) {
  const s = String(status || '')
  if (s === 'DONE') return 'done'
  if (s === 'RUNNING') return 'current'
  if (s === 'FAILED') return 'failed'
  return 'pending'
}

const SPAN_PALETTE = ['#5794f2', '#73bf69', '#d9a441', '#b877d9', '#56b6c2', '#eb7b18', '#8ab8ff']

export function spanColor(agent, state) {
  const st = String(state || '')
  if (st === 'failed') return '#e02f44'
  if (st === 'pending') return '#3d4654'
  const key = String(agent || '')
  let hash = 0
  for (let i = 0; i < key.length; i += 1) hash = ((hash << 5) - hash + key.charCodeAt(i)) | 0
  return SPAN_PALETTE[Math.abs(hash) % SPAN_PALETTE.length]
}

export function clockText(at = Date.now()) {
  try {
    return new Date(at).toLocaleTimeString('en-GB', { hour12: false })
  } catch {
    return ''
  }
}

/** 窗口耗时 KPI：没有 duration 样本显示 —，不编 0ms。
 * @param {unknown} ms
 */
export function durationKpi(ms) {
  if (ms == null || ms === '') return '—'
  const n = Number(ms)
  if (!Number.isFinite(n) || n < 0) return '—'
  return formatDurationMs(n) || '—'
}

/**
 * @param {unknown} pct
 */
export function tokenDeltaText(pct) {
  if (pct == null || pct === '') return '相邻桶 token 环比 — · 不是费用 SLA'
  const n = Number(pct)
  if (!Number.isFinite(n)) return '相邻桶 token 环比 — · 不是费用 SLA'
  const sign = n > 0 ? '+' : ''
  return `相邻桶 token 环比 ${sign}${Math.round(n)}% · 不是费用 SLA`
}

/**
 * @param {unknown} alerts
 */
export function alertRows(alerts) {
  const list = Array.isArray(alerts) ? alerts : (alerts && Array.isArray(alerts.items) ? alerts.items : [])
  return list.map((row) => ({
    id: String(row?.id || row?.title || ''),
    tone: String(row?.tone || 'warn'),
    title: String(row?.title || ''),
    count: Number(row?.count || 0),
    caption: String(row?.caption || '')
  })).filter((row) => row.id || row.title)
}
