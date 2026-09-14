import { publicErrorCode, publicErrorMessage, publicErrorTech } from './public-error'

export const AGENT_NAME = {
  CITATION_INTEGRITY: '引用核验',
  FIGURE_PDF: '图表检查',
  ACADEMIC_REVIEWER: '学术审稿',
  ACADEMIC_STYLE: '语言润色',
  REVISION_PLANNING: '改稿计划',
  REVISION_EXECUTION: '修改执行',
  FINAL_VERIFICATION: '结果复核'
}

/** 三条 Workflow 的 Agent 序列，与 LiteFlow 链一致，不是新状态。 */
export const WORKFLOW_AGENTS = {
  CITATION_ONLY: ['CITATION_INTEGRITY'],
  QUICK_REVIEW: ['CITATION_INTEGRITY', 'ACADEMIC_STYLE'],
  FULL_REVIEW: [
    'CITATION_INTEGRITY',
    'FIGURE_PDF',
    'ACADEMIC_REVIEWER',
    'ACADEMIC_STYLE',
    'REVISION_PLANNING',
    'REVISION_EXECUTION',
    'FINAL_VERIFICATION'
  ]
}

export const PIPE_STATE_LABEL = {
  done: '完成',
  current: '当前',
  pending: '未到',
  failed: '失败'
}

/** Planning 已输出的三类，结果页分组用，不发明新任务类型。 */
export const KIND_META = [
  { key: 'HUMAN_REQUIRED', label: '需要人工处理' },
  { key: 'HYBRID', label: '人机协作' },
  { key: 'AI_AUTOMATABLE', label: '可由系统改' }
]

/** 用户审校页分类。不含 Trace / 可观测。 */
export const TASK_PANELS = [
  { key: 'overview', label: '总览' },
  { key: 'confirm', label: '需你确认' },
  { key: 'issues', label: '问题与证据' },
  { key: 'revise', label: '改稿' },
  { key: 'manuscript', label: '稿件对照' }
]

export const TASK_PANEL_EMPTY = {
  confirm: '这次没有需你确认。',
  issues: '这次没有问题与证据。',
  revise: '这次没有改稿。',
  manuscript: '这次没有稿件对照。'
}

export const DEFAULT_TASK_PANEL = 'confirm'
export const TASK_LIST_PAGE_SIZE = 5
export const FINDING_HIT_ID = 'zy-hit'

export function taskPanelOf(raw) {
  const key = String(raw || '')
  return TASK_PANELS.some((p) => p.key === key) ? key : DEFAULT_TASK_PANEL
}

export function taskPanelCounts({
  artifacts,
  humanIssues,
  humanTasks,
  restIssues,
  evidence,
  autoTasks,
  autoPatches,
  displayPatches
} = {}) {
  return {
    overview: (artifacts || []).length,
    confirm: (humanTasks || []).length + (humanIssues || []).length,
    issues: (restIssues || []).length + (evidence || []).length,
    revise: (autoTasks || []).length + (autoPatches || []).length,
    manuscript: (displayPatches || []).length
  }
}

export function findingAnchorOf(issue) {
  if (!issue) return ''
  return readAnchor(issue.location) || String(issue.doi || '').trim() || String(issue.id || '').trim()
}

export function pageSlice(items, page, size = TASK_LIST_PAGE_SIZE) {
  const list = Array.isArray(items) ? items : []
  const s = Math.max(1, Number(size) || TASK_LIST_PAGE_SIZE)
  const pages = Math.max(1, Math.ceil(list.length / s) || 1)
  const p = Math.min(pages, Math.max(1, Number(page) || 1))
  return {
    items: list.slice((p - 1) * s, p * s),
    page: p,
    size: s,
    total: list.length,
    pages
  }
}

export function taskKindOfIssue(issue, taskList) {
  const id = issue?.id
  if (!id) return ''
  const hit = (taskList || []).find((t) => t.issueId && t.issueId === id)
  return hit?.kind || ''
}

export function isHumanRequiredIssue(issue, taskList) {
  return taskKindOfIssue(issue, taskList) === 'HUMAN_REQUIRED'
}

export function humanRequiredIssues(issues, taskList) {
  return (issues || []).filter((item) => isHumanRequiredIssue(item, taskList))
}

export function otherIssues(issues, taskList) {
  return (issues || []).filter((item) => !isHumanRequiredIssue(item, taskList))
}

export function patchTaskKind(p, issueList, taskList) {
  const issue = findRelatedIssue(p, issueList || [])
  const hit = (taskList || []).find((t) =>
    (p?.issueId && t.issueId === p.issueId) || (issue && t.issueId === issue.id)
  )
  return hit?.kind || ''
}

export function humanRequiredPatches(patches, issueList, taskList) {
  return (patches || []).filter((p) => patchTaskKind(p, issueList, taskList) === 'HUMAN_REQUIRED')
}

export function revisablePatches(patches, issueList, taskList) {
  return (patches || []).filter((p) => patchTaskKind(p, issueList, taskList) !== 'HUMAN_REQUIRED')
}

/** 任务/问题卡片对应的 Patch；本身已是 Patch 则原样返回。 */
export function relatedPatch(item, patches) {
  if (!item) return null
  const list = Array.isArray(patches) ? patches : []
  if (Object.prototype.hasOwnProperty.call(item, 'original') || Object.prototype.hasOwnProperty.call(item, 'proposed')) {
    if (String(item.original || '') || String(item.proposed || '')) return item
  }
  const issueId = String(item.issueId || item.id || '')
  if (!issueId) return null
  return list.find((p) => p.issueId && p.issueId === issueId) || null
}

export function humanKindGroups(taskList) {
  return revisionKindGroups(taskList).filter((g) => g.key === 'HUMAN_REQUIRED')
}

export function autoKindGroups(taskList) {
  return revisionKindGroups(taskList).filter((g) => g.key !== 'HUMAN_REQUIRED')
}

export const CAT_META = [
  { key: 'CITATION', label: '引用核验' },
  { key: 'FIGURE_PDF', label: '图表' },
  { key: 'REVIEW', label: '审稿' },
  { key: 'STYLE', label: '润色' },
  { key: 'FORMAT', label: '格式' },
  { key: 'OTHER', label: '其他修改' }
]

export const SEV_META = [
  { key: 'CRITICAL', label: '必须处理' },
  { key: 'HIGH', label: '高' },
  { key: 'NOT_VERIFIED', label: '未能核实' },
  { key: 'MEDIUM', label: '中' },
  { key: 'LOW', label: '低' },
  { key: 'OTHER', label: '其他' }
]

export const MIN_NEEDLE = 8
export const CONTEXT_PAD = 140

export function sevKey(s) {
  const t = String(s || '')
  if (SEV_META.some((m) => m.key === t && m.key !== 'OTHER')) return t
  return 'OTHER'
}

export function severityLabel(s) {
  return { CRITICAL: '必须处理', HIGH: '高', MEDIUM: '中', LOW: '低', NOT_VERIFIED: '未能核实' }[s] || (s || '提示')
}

export function collect(list) {
  const issues = []
  const patches = []
  const tasks = []
  const seenIssue = new Set()
  const seenPatch = new Set()
  const seenTask = new Set()
  const typedIssue = list.some((a) => a.artifactType === 'ReviewIssue')
  for (const a of list) {
    const body = unwrap(a.payload)
    const agent = AGENT_NAME[a.agent] || a.agent
    if (a.artifactType === 'ReviewIssue' || (!typedIssue && a.artifactType === 'Bundle')) {
      for (const node of asArray(a.artifactType === 'Bundle' ? body?.issues : body)) {
        const item = toIssue(node, agent)
        if (item && !seenIssue.has(item.id)) {
          seenIssue.add(item.id)
          issues.push(item)
        }
      }
    }
    if (a.artifactType === 'RevisionPatch' || a.artifactType === 'Bundle') {
      for (const node of patchNodes(a.artifactType, body)) {
        const p = toPatch(node)
        if (p && !seenPatch.has(p.key)) {
          seenPatch.add(p.key)
          patches.push(p)
        }
      }
    }
    if (a.artifactType === 'RevisionTask' || a.artifactType === 'Bundle') {
      const rows = a.artifactType === 'Bundle'
        ? asArray(body?.revisionTasks || body?.tasks)
        : asArray(body)
      for (const node of rows) {
        const t = toTask(node)
        if (t && !seenTask.has(t.id || t.issueId + t.instruction)) {
          seenTask.add(t.id || t.issueId + t.instruction)
          tasks.push(t)
        }
      }
    }
  }
  if (!issues.length) {
    for (const a of list) {
      if (a.artifactType !== 'Evidence' && a.artifactType !== 'Bundle') continue
      const body = unwrap(a.payload)
      const rows = a.artifactType === 'Bundle' ? asArray(body?.evidence) : asArray(body)
      for (const node of rows) {
        const status = String(node?.status || '')
        if (!status || status === 'VERIFIED') continue
        const item = toIssue({
          issueId: node.evidenceId,
          severity: status,
          category: 'CITATION',
          summary: node.claim || '这条证据未能核实',
          detail: node.excerpt,
          excerpt: node.excerpt,
          doi: doiOf(node.claim) || doiOf(node.doi) || pickText(node.doi),
          location: isPlainObject(node.location) ? node.location : undefined,
          evidenceIds: node.evidenceId ? [node.evidenceId] : [],
          rationale: pickText(node.rationale, node.reason),
          humanRequiredReason: pickText(node.humanRequiredReason)
        }, AGENT_NAME[a.agent] || a.agent)
        if (item && !seenIssue.has(item.id)) {
          seenIssue.add(item.id)
          issues.push(item)
        }
      }
    }
  }
  const evidence = collectEvidence(list)
  const parsed = { issues, patches, tasks, evidence }
  enrichPriority(parsed)
  return parsed
}

export function toIssue(node, agent) {
  if (!node || typeof node !== 'object') return null
  const summary = pickText(
    node.summary, node.message, node.title, node.issue,
    node.issueDescription, node.description, node.recommendation
  )
  if (!summary) return null
  const originalText = pickText(node.originalText, node.original_text, node.original)
  const detail = pickText(node.detail, node.issueDescription, node.description, node.recommendation)
  const category = String(node.category || '')
  const doi = pickText(node.doi) || doiOf(summary) || doiOf(detail) || doiOf(originalText) || doiOf(pickText(node.excerpt, node.quote))
  const quote = displayQuote(node, originalText, doi)
  return {
    id: String(node.issueId || node.id || `${agent}:${summary}`),
    agent,
    severity: String(node.severity || node.status || ''),
    summary,
    quote: isJunk(quote) ? '' : quote,
    action: actionOf(category || '', quote || doi),
    category,
    detail,
    originalText,
    excerpt: pickText(node.excerpt, node.quote, node.span),
    doi,
    rationale: pickText(node.rationale, node.reason),
    humanRequiredReason: pickText(node.humanRequiredReason),
    evidenceIds: idList(node.evidenceIds),
    section: pickText(node.section),
    location: isPlainObject(node.location) ? node.location : null,
    sourceAgent: pickText(node.sourceAgent),
    high: false,
    whyHigh: '',
    suggestFix: '',
    evidenceView: { location: '', excerpt: '', basis: '' }
  }
}

export function toPatch(node) {
  if (!node || typeof node !== 'object') return null
  const original = pickText(
    node.originalText, node.original_text, node.original, node.oldText, node.old_text,
    node.before, node.sourceText, node.fromText, node.currentText, node.deleted,
    node.old, node.src, node['原文'], node['现在']
  )
  const proposed = pickText(
    node.proposedText, node.proposed_text, node.proposed, node.newText, node.new_text,
    node.after, node.targetText, node.toText, node.rewritten, node.replacement,
    node.revised, node.added, node.new, node['建议'], node['改写'], node['修改后']
  )
  if (!original && !proposed) return null
  if (original === proposed) return null
  return {
    key: String(node.patchId || original + '→' + proposed),
    original,
    proposed,
    reason: pickText(node.reason, node.rationale, node.explanation, node.comment, node.instruction),
    rationale: pickText(node.rationale, node.reason),
    issueId: String(node.issueId || ''),
    category: String(node.category || ''),
    evidenceIds: idList(node.evidenceIds),
    location: isPlainObject(node.location) ? node.location : null,
    high: false,
    whyHigh: '',
    suggestFix: '',
    evidenceView: { location: '', excerpt: '', basis: '' }
  }
}

export function toTask(node) {
  if (!isPlainObject(node)) return null
  const instruction = pickText(node.instruction, node.reason, node.guidance)
  const issueId = String(node.issueId || '')
  if (!instruction && !issueId) return null
  const kind = String(node.kind || '').toUpperCase()
  return {
    id: String(node.taskId || node.id || ''),
    issueId,
    instruction,
    kind: KIND_META.some((m) => m.key === kind) ? kind : '',
    rationale: pickText(node.rationale, node.reason),
    humanRequiredReason: pickText(node.humanRequiredReason),
    protectedFacts: idList(node.protectedFacts),
    high: false,
    whyHigh: '',
    suggestFix: '',
    evidenceView: { location: '', excerpt: '', basis: '' }
  }
}

export function toEvidence(node) {
  if (!isPlainObject(node)) return null
  const id = pickText(node.evidenceId, node.id)
  const claim = pickText(node.claim)
  const excerpt = pickText(node.excerpt)
  if (!id && !claim && !excerpt) return null
  return {
    id,
    claim,
    source: pickText(node.source),
    excerpt,
    supportsClaim: node.supportsClaim,
    confidence: node.confidence,
    status: String(node.status || ''),
    location: isPlainObject(node.location) ? node.location : null
  }
}

function collectEvidence(list) {
  const rows = []
  const seen = new Set()
  for (const a of Array.isArray(list) ? list : []) {
    if (a.artifactType !== 'Evidence' && a.artifactType !== 'Bundle') continue
    const body = unwrap(a.payload)
    const items = a.artifactType === 'Bundle' ? asArray(body?.evidence) : asArray(body)
    for (const node of items) {
      const item = toEvidence(node)
      if (!item) continue
      const key = item.id || `${item.claim}|${item.excerpt}`
      if (seen.has(key)) continue
      seen.add(key)
      rows.push(item)
    }
  }
  return rows
}

function idList(v) {
  if (Array.isArray(v)) return v.map((x) => String(x ?? '').trim()).filter(Boolean)
  if (typeof v === 'string' && v.trim()) return [v.trim()]
  return []
}

/** HIGH / CRITICAL / NOT_VERIFIED，或关联 RevisionTask 为 HUMAN_REQUIRED。 */
export function isHighPriority(issue, task) {
  const sev = String(issue?.severity || '')
  if (sev === 'HIGH' || sev === 'CRITICAL' || sev === 'NOT_VERIFIED') return true
  return String(task?.kind || '') === 'HUMAN_REQUIRED'
}

const WHY_CITATION = '优先级高：会改引用结论与作者责任。未能核实的 DOI / 关键引用最终选择须由作者处理，系统不能替你选定文献。'
const WHY_REVIEW = '优先级高：会改实验结论或数据表述。补实验、改真实数据、改研究方法属于人机边界，须作者确认。'
const WHY_FIGURE = '优先级高：会改图表或投稿规格结论。页规格、DPI、题注与编号影响 camera-ready，须作者确认。'
const WHY_FORMAT = '优先级高：会改投稿规格结论。版式与页规格须作者确认后再改正式稿。'
const WHY_STYLE = '优先级高：可能改作者表述责任。数字、公式、引用和原结论须保护，不能只当润色看。'
const WHY_HUMAN = '优先级高：人机边界要求作者处理——补实验、重跑代码、修改真实数据、改研究方法或关键引用最终选择。'
const WHY_FALLBACK = '优先级高：会改结论、数据、引用或作者责任，不能只当提示看。'

function whyHighByCategory(category) {
  const c = String(category || '')
  if (c === 'CITATION') return WHY_CITATION
  if (c === 'REVIEW' || c === 'LOGIC') return WHY_REVIEW
  if (c === 'FIGURE_PDF') return WHY_FIGURE
  if (c === 'FORMAT') return WHY_FORMAT
  if (c === 'STYLE') return WHY_STYLE
  return ''
}

/**
 * 展示用 whyHigh：优先 Artifact 的 humanRequiredReason / rationale，缺则按严重度与人机边界从现有字段推导。
 */
export function whyHighOf({ issue, task } = {}) {
  const explicit = pickText(
    issue?.humanRequiredReason,
    task?.humanRequiredReason,
    issue?.rationale,
    task?.rationale
  )
  if (explicit) return explicit
  if (String(task?.kind || '') === 'HUMAN_REQUIRED') {
    return whyHighByCategory(issue?.category) || WHY_HUMAN
  }
  return whyHighByCategory(issue?.category) || WHY_FALLBACK
}

export function suggestFixOf({ issue, task, patch } = {}) {
  const fromTask = pickText(task?.instruction)
  if (fromTask) return fromTask
  const fromAction = pickText(issue?.action)
  if (fromAction) return fromAction
  const proposed = pickText(patch?.proposed)
  const reason = pickText(patch?.reason, patch?.rationale)
  if (proposed && reason) return `改为「${clip(proposed, 80)}」。${reason}`
  if (reason) return reason
  const detail = pickText(issue?.detail)
  if (detail && detail !== pickText(issue?.summary)) return detail
  return actionOf(issue?.category || '', issue?.quote || issue?.doi)
}

export function formatLocation(node) {
  if (!node) return ''
  const parts = []
  const section = pickText(node.section)
  if (section) parts.push(section)
  const loc = node.location
  const anchor = readAnchor(loc)
  if (anchor) parts.push(anchor)
  if (isPlainObject(loc)) {
    const s = loc.startOffset
    const e = loc.endOffset
    if (s != null && s !== '' && e != null && e !== '') {
      parts.push(`offset ${s}–${e}`)
    }
  }
  return parts.join(' · ')
}

export function evidenceView(issue, evidenceList, patch) {
  const list = Array.isArray(evidenceList) ? evidenceList : []
  const byId = Object.fromEntries(list.filter((e) => e?.id).map((e) => [e.id, e]))
  const linked = []
  const seen = new Set()
  for (const id of [...idList(issue?.evidenceIds), ...idList(patch?.evidenceIds)]) {
    const hit = byId[id]
    if (hit && !seen.has(hit.id)) {
      seen.add(hit.id)
      linked.push(hit)
    }
  }
  const locParts = []
  const issueLoc = formatLocation(issue)
  if (issueLoc) locParts.push(issueLoc)
  const patchLoc = formatLocation(patch)
  if (patchLoc && !locParts.includes(patchLoc)) locParts.push(patchLoc)
  for (const ev of linked) {
    const el = formatLocation(ev)
    if (el && !locParts.includes(el)) locParts.push(el)
  }
  const excerpt = pickText(
    issue?.originalText,
    issue?.excerpt,
    issue?.quote,
    patch?.original,
    ...linked.map((e) => e.excerpt)
  )
  const basisParts = []
  for (const ev of linked) {
    const bits = []
    if (ev.id) bits.push(`Evidence ${ev.id}`)
    if (ev.source) bits.push(ev.source)
    if (ev.status) bits.push(ev.status)
    if (ev.claim) bits.push(ev.claim)
    if (bits.length) basisParts.push(bits.join(' · '))
  }
  const detail = pickText(issue?.detail)
  if (detail && detail !== pickText(issue?.summary) && !basisParts.includes(detail)) {
    basisParts.push(detail)
  }
  const rationale = pickText(issue?.rationale, issue?.humanRequiredReason)
  if (rationale && !basisParts.includes(rationale)) basisParts.push(rationale)
  return {
    location: locParts.join('；'),
    excerpt: excerpt ? clip(excerpt, 160) : '',
    basis: basisParts.join('；')
  }
}

export function enrichPriority(parsed) {
  const issues = parsed?.issues || []
  const patches = parsed?.patches || []
  const tasks = parsed?.tasks || []
  const evidence = parsed?.evidence || []
  for (const issue of issues) {
    const task = tasks.find((t) => t.issueId && t.issueId === issue.id)
    const patch = patches.find((p) => p.issueId && p.issueId === issue.id)
    const high = isHighPriority(issue, task)
    issue.high = high
    issue.whyHigh = high ? whyHighOf({ issue, task }) : ''
    issue.suggestFix = high ? suggestFixOf({ issue, task, patch }) : (issue.action || '')
    issue.evidenceView = evidenceView(issue, evidence, patch)
  }
  for (const task of tasks) {
    const issue = issues.find((i) => i.id === task.issueId)
    const patch = patches.find((p) => task.issueId && p.issueId === task.issueId)
    const high = task.kind === 'HUMAN_REQUIRED' || isHighPriority(issue, task)
    task.high = high
    task.whyHigh = high ? whyHighOf({ issue, task }) : ''
    task.suggestFix = high ? suggestFixOf({ issue, task, patch }) : ''
    task.evidenceView = evidenceView(issue, evidence, patch)
  }
  for (const patch of patches) {
    const issue = findRelatedIssue(patch, issues)
    const task = tasks.find((t) => (patch.issueId && t.issueId === patch.issueId) || (issue && t.issueId === issue.id))
    const high = isHighPriority(issue, task)
    patch.high = high
    patch.whyHigh = high ? whyHighOf({ issue, task }) : ''
    patch.suggestFix = high ? suggestFixOf({ issue, task, patch }) : (patch.reason || '')
    patch.evidenceView = evidenceView(issue, evidence, patch)
  }
  return parsed
}

export function showCheckpointPipeline(status) {
  return status === 'PENDING' || status === 'RUNNING' || status === 'FAILED'
}

/** 任务页始终展示 Agent 时间线，含 DONE / WAITING_ACCEPT 的耗时与 checkpoint。 */
export function showAgentTrace(status) {
  return ['PENDING', 'RUNNING', 'FAILED', 'DONE', 'WAITING_ACCEPT'].includes(String(status || ''))
}

export function formatDurationMs(ms) {
  if (ms == null || ms === '') return ''
  const n = Number(ms)
  if (!Number.isFinite(n) || n < 0) return ''
  if (n < 1000) return `${Math.round(n)} ms`
  return `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)} s`
}

export function mergeTraceNodes(nodes, trace) {
  const list = Array.isArray(trace?.nodes) ? trace.nodes : []
  const by = Object.fromEntries(list.map((s) => [s.agent, s]))
  return (nodes || []).map((n) => {
    const s = by[n.id] || {}
    const durationMs = s.durationMs ?? null
    const tokens = s.tokens ?? null
    const fencingToken = s.fencingToken ?? null
    const checkpoint = Boolean(s.checkpoint)
    const rawError = s.errorMessage || n.errorMessage
    const failed = n.state === 'failed'
    const skillVersion = s.skillVersion || ''
    const promptVersion = s.promptVersion || ''
    return {
      ...n,
      durationMs,
      tokens,
      fencingToken,
      checkpoint,
      skipped: Boolean(s.skipped),
      startedAt: s.startedAt ?? null,
      endedAt: s.endedAt ?? null,
      skillVersion,
      promptVersion,
      errorMessage: failed ? publicErrorMessage(rawError) : '',
      errorCode: failed ? (s.errorCode || publicErrorCode(rawError) || '') : '',
      errorTech: failed ? publicErrorTech(rawError) : '',
      meta: [
        formatDurationMs(durationMs),
        tokens != null && tokens !== '' ? `${tokens} token` : '',
        checkpoint ? 'checkpoint' : '',
        s.skipped ? 'checkpoint 跳过' : '',
        fencingToken != null && fencingToken !== '' ? `fence ${fencingToken}` : '',
        skillVersion ? `skill ${skillVersion}` : '',
        promptVersion ? `prompt ${promptVersion}` : '',
        failed && (s.errorCode || n.errorCode) ? (s.errorCode || n.errorCode) : ''
      ].filter(Boolean).join(' · ')
    }
  })
}

/** 任务页 Trace 顶栏：当前 fencing / lease，便于核对抢占，不是 SLA。 */
export function fenceLabel(task, trace) {
  const token = trace?.fencingToken ?? task?.fencingToken
  if (token == null || token === '') return ''
  const lease = trace?.lease
  if (lease && (lease.owner || lease.fencingToken != null)) {
    const leaseToken = lease.fencingToken ?? token
    return `fencing ${token} · lease ${leaseToken}`
  }
  return `fencing ${token}`
}

/**
 * checkpoint_agent 是已完成的最后一个节点。据此标完成 / 当前 / 未到；失败节点带 errorMessage。
 */
export function pipelineNodes(workflow, checkpointAgent, status, errorMessage) {
  const agents = WORKFLOW_AGENTS[workflow] || []
  const doneIdx = agents.indexOf(String(checkpointAgent || ''))
  return agents.map((id, i) => {
    let state = 'pending'
    if (status === 'DONE' || status === 'WAITING_ACCEPT') {
      state = 'done'
    } else if (i <= doneIdx) {
      state = 'done'
    } else if (i === doneIdx + 1) {
      state = status === 'FAILED' ? 'failed' : 'current'
    }
    return {
      id,
      name: AGENT_NAME[id] || id,
      state,
      label: PIPE_STATE_LABEL[state] || state,
      errorMessage: state === 'failed' ? publicErrorMessage(errorMessage) : '',
      errorCode: state === 'failed' ? publicErrorCode(errorMessage) : '',
      errorTech: state === 'failed' ? publicErrorTech(errorMessage) : ''
    }
  })
}

export function revisionKindGroups(taskList) {
  const groups = KIND_META.map((m) => ({ ...m, items: [] }))
  const byKey = Object.fromEntries(groups.map((g) => [g.key, g]))
  for (const t of taskList || []) {
    const g = byKey[t.kind]
    if (g) g.items.push(t)
  }
  return groups.filter((g) => g.items.length)
}

export function meaningfulPatch(p) {
  const original = String(p?.original || '').trim()
  const proposed = String(p?.proposed || '').trim()
  return Boolean(original || proposed) && original !== proposed
}

export function isPlainObject(v) {
  return v != null && typeof v === 'object' && !Array.isArray(v)
    && Object.prototype.toString.call(v) === '[object Object]'
}

export function pickText(...vals) {
  for (const v of vals) {
    if (typeof v === 'string') {
      const s = v.trim()
      if (!s || s.includes('[native code]') || s.startsWith('function ')) continue
      return s
    }
    if (isPlainObject(v)) {
      const nested = pickText(v.text, v.content, v.value, v.span, v.excerpt)
      if (nested) return nested
    }
  }
  return ''
}

export function readAnchor(location) {
  if (!isPlainObject(location)) return ''
  const a = location.anchor
  if (typeof a !== 'string') return ''
  const s = a.trim()
  if (!s || s.includes('[native code]') || s.startsWith('function ')) return ''
  return s
}

export function displayQuote(node, originalText, doi) {
  const excerpt = pickText(node.excerpt, node.quote, node.span)
  if (originalText) return clip(originalText, 120)
  if (excerpt && !looksLikeSectionLabel(excerpt)) return excerpt
  if (doi) return doi
  const loc = node?.location
  const anchor = readAnchor(loc)
  if (anchor) return anchor
  if (isPlainObject(loc)) {
    const fromLoc = pickText(loc.excerpt, loc.text, loc.quote, loc.span)
    if (fromLoc) return fromLoc
  }
  if (typeof loc === 'string') {
    const s = loc.trim()
    if (s && !s.includes('[native code]') && !s.startsWith('function ')) return s
  }
  return excerpt || ''
}

export function patchNodes(artifactType, body) {
  if (artifactType === 'Bundle') {
    return asArray(body?.patches || body?.revisions || body?.edits || body?.hunks)
  }
  if (artifactType === 'RevisionPatch') {
    if (Array.isArray(body)) return body
    if (isPlainObject(body)) {
      if (body.patches || body.revisions || body.edits) {
        return asArray(body.patches || body.revisions || body.edits)
      }
      return [body]
    }
  }
  return []
}

export function actionOf(category, quote) {
  if (category === 'CITATION' || quote?.startsWith('10.')) {
    return quote ? `请在正文和参考文献中核对或替换「${quote}」。引用核验不会自动改正文。` : '请核对这条引用。引用核验不会自动改正文。'
  }
  if (category === 'FIGURE_PDF') return '请检查图表清晰度、题注和编号。'
  if (category === 'REVIEW' || category === 'LOGIC') return '请补实验、论据，或改掉过强的表述。'
  if (category === 'STYLE') return '建议按改写稿调整措辞，数字和公式不要动。'
  if (category === 'FORMAT') return '请按会议模板调整版式。'
  return quote ? '点这里，右侧会标出原文位置。' : '请根据说明自行修改。'
}

export function unwrap(payload) {
  let data = payload
  if (typeof data === 'string') {
    try { data = JSON.parse(data) } catch { return payload }
  }
  if (data && typeof data === 'object' && data.body !== undefined) return data.body
  return data
}

export function asArray(node) {
  if (node == null || node === '') return []
  if (Array.isArray(node)) return node
  if (typeof node === 'object') return [node]
  return []
}

export function doiOf(text) {
  const m = String(text || '').match(/10\.\d{4,9}\/[^\s，。；;,)]+/i)
  return m ? m[0].replace(/[.,;]+$/, '') : ''
}

export function isDoiToken(s) {
  const t = String(s || '').trim()
  if (!t) return false
  return /^10\.\d{4,9}\/\S+$/i.test(t) || doiOf(t) === t
}

export function isJunk(s) {
  const t = String(s || '')
  return !t.trim() || t.includes('[native code]') || t.startsWith('function ')
}

export function looksLikeSectionLabel(s) {
  const t = String(s || '').trim()
  if (!t || t.length > 80) return false
  if (doiOf(t)) return false
  return /^(abstract|introduction|method|experiments|conclusion|references|section)\b/i.test(t)
    || /section\s+\d/i.test(t)
}

export function clip(s, n = 88) {
  const t = String(s || '').replace(/\s+/g, ' ').trim()
  if (!t) return '（原文为空，这是新增）'
  return t.length > n ? t.slice(0, n) + '…' : t
}

export function stripEllipsis(s) {
  return String(s || '').replace(/[.…]+$/u, '').replace(/\.{3}$/, '').trim()
}

export function expandCandidates(raw) {
  const s = String(raw || '').trim()
  if (!s || isJunk(s)) return []
  const out = [s]
  const stripped = stripEllipsis(s)
  if (stripped && stripped !== s) out.push(stripped)
  for (const p of s.split(/\.{3}|…/)) {
    const t = p.trim()
    if (t.length >= 12) out.push(t)
  }
  return out
}

export function includesCI(text, needle) {
  return text.toLowerCase().indexOf(String(needle).toLowerCase()) >= 0
}

export function findAllIndexes(text, needle) {
  const out = []
  if (!text || !needle) return out
  const lower = text.toLowerCase()
  const n = String(needle).toLowerCase()
  let i = 0
  while (i <= lower.length - n.length) {
    const j = lower.indexOf(n, i)
    if (j < 0) break
    out.push(j)
    i = j + Math.max(n.length, 1)
  }
  return out
}

export function locateNeedle(text, original) {
  if (!text) return []
  for (const c of expandCandidates(original)) {
    if (isDoiToken(c)) {
      const hits = findAllIndexes(text, c)
      if (hits.length) return hits
      continue
    }
    if (c.length < MIN_NEEDLE) continue
    const hits = findAllIndexes(text, c)
    if (hits.length) return hits
  }
  const doi = doiOf(original)
  if (doi) return findAllIndexes(text, doi)
  return []
}

export function spotsNeedle(text, original, index) {
  for (const c of expandCandidates(original)) {
    if (c.length < MIN_NEEDLE && !isDoiToken(c)) continue
    if (text.toLowerCase().slice(index, index + c.length) === c.toLowerCase()) return c
  }
  const doi = doiOf(original)
  if (doi && text.toLowerCase().slice(index, index + doi.length) === doi.toLowerCase()) return doi
  return original
}

export function pickFindingNeedle(issue, text) {
  if (!issue || !text) return null
  const cands = []
  const locAnchor = readAnchor(issue.location)
  if (locAnchor) {
    for (const c of expandCandidates(locAnchor)) cands.push(c)
  }
  for (const raw of [issue.originalText, issue.excerpt, issue.quote, issue.doi]) {
    for (const c of expandCandidates(raw)) cands.push(c)
  }
  const extraDoi = issue.doi || doiOf(issue.summary) || doiOf(issue.detail) || doiOf(issue.quote)
  if (extraDoi) cands.push(extraDoi)
  for (const c of cands) {
    if (isJunk(c) || looksLikeSectionLabel(c)) continue
    if (isDoiToken(c) || (doiOf(c) && c.length < 48)) {
      const d = isDoiToken(c) ? c : doiOf(c)
      if (includesCI(text, d)) return { needle: d, all: true, expand: true }
    }
    if (c.length < MIN_NEEDLE) continue
    if (includesCI(text, c)) return { needle: c, all: false, expand: c.length < 48 }
  }
  const blob = [issue.summary, issue.detail, issue.quote].join(' ')
  const extra = blob.match(/Figure\s+\d+|Table\s+\d+|US Letter|612\s*[x×]\s*792/i)
  if (extra && includesCI(text, extra[0])) return { needle: extra[0], all: false, expand: true }
  return null
}

export function expandRange(text, start, end) {
  let s = start
  let e = end
  while (s > 0 && start - s < CONTEXT_PAD) {
    const prev = text[s - 1]
    if (prev === '\n' && (s < 2 || text[s - 2] === '\n')) break
    if ('。！？.!?'.includes(prev) && start - s > 24) break
    s--
  }
  while (e < text.length && e - end < CONTEXT_PAD) {
    const ch = text[e]
    if (ch === '\n' && text[e + 1] === '\n') break
    e++
    if ('。！？.!?'.includes(ch) && e - end > 8) break
  }
  while (s < start && /\s/.test(text[s])) s++
  return { start: s, end: e }
}

export function paintRanges(text, ranges, activeKey) {
  const bounds = new Set([0, text.length])
  for (const r of ranges) {
    if (r.start < r.end) {
      bounds.add(Math.max(0, r.start))
      bounds.add(Math.min(text.length, r.end))
    }
  }
  const points = [...bounds].sort((a, b) => a - b)
  const parts = []
  for (let i = 0; i < points.length - 1; i++) {
    const start = points[i]
    const end = points[i + 1]
    if (start === end) continue
    const covering = ranges.filter((r) => r.start <= start && r.end >= end)
    const hit = covering.some((r) => r.kind === 'finding')
    const patchCovering = covering.filter((r) => r.kind === 'patch')
    const active = patchCovering.find((r) => r.key === activeKey)
    const patch = active || patchCovering.slice().sort((a, b) => (a.end - a.start) - (b.end - b.start))[0]
    parts.push({
      text: text.slice(start, end),
      hit,
      patchKey: patch?.key || '',
      patchOn: Boolean(patch && patch.key === activeKey)
    })
  }
  return parts
}

export function catKeyOfIssue(item) {
  const c = item?.category || ''
  if (c === 'CITATION') return 'CITATION'
  if (c === 'FIGURE_PDF') return 'FIGURE_PDF'
  if (c === 'REVIEW' || c === 'LOGIC') return 'REVIEW'
  if (c === 'STYLE') return 'STYLE'
  if (c === 'FORMAT') return 'FORMAT'
  const a = item?.agent || ''
  if (a === '引用核验') return 'CITATION'
  if (a === '图表检查') return 'FIGURE_PDF'
  if (a === '学术审稿') return 'REVIEW'
  if (a === '语言润色') return 'STYLE'
  return 'OTHER'
}

export function catKeyOfPatch(p, issueList) {
  if (p.category) {
    const mapped = catKeyOfIssue({ category: p.category })
    if (mapped !== 'OTHER') return mapped
  }
  const issue = findRelatedIssue(p, issueList)
  if (issue) {
    const mapped = catKeyOfIssue(issue)
    if (mapped !== 'OTHER') return mapped
  }
  const id = p.issueId || ''
  if (/citation/i.test(id) || doiOf(p.original)) return 'CITATION'
  if (/figure/i.test(id)) return 'FIGURE_PDF'
  if (/style/i.test(id)) return 'STYLE'
  if (/review|logic/i.test(id)) return 'REVIEW'
  if (/format/i.test(id)) return 'FORMAT'
  return 'OTHER'
}

export function findRelatedIssue(p, issueList) {
  if (p.issueId) {
    const hit = issueList.find((i) => i.id === p.issueId)
    if (hit) return hit
  }
  const doi = doiOf(p.original) || doiOf(p.proposed)
  if (doi) {
    const hit = issueList.find((i) => i.doi === doi || (i.quote && i.quote.includes(doi)) || (i.summary && i.summary.includes(doi)) || (i.detail && i.detail.includes(doi)))
    if (hit) return hit
  }
  const orig = String(p.original || '')
  if (orig.length >= 12) {
    const head = orig.slice(0, 40)
    const hit = issueList.find((i) => {
      const blob = [i.originalText, i.quote, i.summary, i.detail].join('\n')
      return blob.includes(head) || (i.originalText && orig.includes(stripEllipsis(i.originalText).slice(0, 40)))
    })
    if (hit) return hit
  }
  return null
}

export function whyOf(p, issueList, taskList) {
  const r = String(p.reason || '').trim()
  if (r && !r.includes('[native code]') && !r.startsWith('function ')) return r
  const issue = findRelatedIssue(p, issueList)
  if (issue) {
    const s = [issue.summary, issue.detail].filter(Boolean).join(' ')
    if (s) return s
  }
  const task = taskList.find((t) => (p.issueId && t.issueId === p.issueId) || (issue && t.issueId === issue.id))
  if (task?.instruction) return task.instruction
  if (issue?.action) return issue.action
  return '系统建议改这一处，以便与审校意见一致。'
}

export function paragraphHunks(a, b) {
  if (!a || !b || a === b) return []
  const ap = a.split(/\n{2,}/)
  const bp = b.split(/\n{2,}/)
  const out = []
  const m = Math.max(ap.length, bp.length)
  for (let i = 0; i < m; i++) {
    const original = (ap[i] || '').trim()
    const proposed = (bp[i] || '').trim()
    if (!original && !proposed) continue
    if (original !== proposed) out.push({ key: original + '→' + proposed, original, proposed, reason: '', issueId: '', category: '' })
  }
  return out.slice(0, 12)
}

/** 导出时把 envelope 字符串解析成对象，便于直接打开 JSON。 */
export function parseArtifactPayload(payload) {
  if (payload == null || payload === '') return payload ?? ''
  if (typeof payload === 'object') return payload
  if (typeof payload === 'string') {
    try { return JSON.parse(payload) } catch { return payload }
  }
  return payload
}

export function toExportArtifacts(list) {
  return (Array.isArray(list) ? list : []).map((a) => ({
    id: a?.id ?? null,
    agent: a?.agent || '',
    artifactType: a?.artifactType || '',
    fencingToken: a?.fencingToken ?? 0,
    payload: parseArtifactPayload(a?.payload)
  }))
}

export function artifactDownloadName(taskId, artifact) {
  const id = taskId == null || taskId === '' ? 'task' : String(taskId)
  const agent = String(artifact?.agent || 'agent').replace(/[^\w.-]+/g, '_')
  const type = String(artifact?.artifactType || 'artifact').replace(/[^\w.-]+/g, '_')
  const aid = artifact?.id != null ? String(artifact.id) : 'x'
  return `zhiyun-${id}-${agent}-${type}-${aid}.json`
}

function collectUnverifiedEvidence(list) {
  const rows = []
  const seen = new Set()
  for (const a of Array.isArray(list) ? list : []) {
    if (a.artifactType !== 'Evidence' && a.artifactType !== 'Bundle') continue
    const body = unwrap(a.payload)
    const items = a.artifactType === 'Bundle' ? asArray(body?.evidence) : asArray(body)
    for (const node of items) {
      if (String(node?.status || '') !== 'NOT_VERIFIED') continue
      const id = pickText(node.evidenceId, node.id)
      const claim = pickText(node.claim, node.excerpt)
      const key = id + '|' + claim
      if (!seen.add(key)) continue
      rows.push({ id, claim, extra: pickText(node.excerpt) })
    }
  }
  return rows
}

function clipExport(s, n) {
  const t = String(s || '').replace(/\s+/g, ' ').trim()
  if (t.length <= n) return t
  return t.slice(0, n) + '…'
}

/**
 * 用当前任务已加载的 Artifact 拼 Markdown。/report 不可用时仍能导出，不另建报告服务。
 */
export function buildReportMarkdown(task = {}, artifacts = [], extras = {}) {
  const parsed = collect(artifacts)
  const workflowName = extras.workflowName || task.workflow || '审校'
  const title = extras.manuscriptTitle || extras.title || '论文'
  const lines = ['# 审校结果汇总', '', '## 任务', '']
  lines.push(`- 任务 ID：${task.id ?? '—'}`)
  lines.push(`- 稿件：${title}`)
  lines.push(`- 稿件 ID：${task.manuscriptId ?? '—'}`)
  lines.push(`- 检查范围：${workflowName} (\`${task.workflow || '—'}\`)`)
  lines.push(`- 状态：${task.status || '—'}`)
  lines.push(`- 源版本：${task.sourceVersion ?? '—'}`)
  lines.push(`- 候选版本：${task.candidateVersion ?? '—'}`)
  lines.push(`- 检查点：${task.checkpointAgent || '—'}`)
  const safeError = publicErrorMessage(task.errorMessage)
  if (safeError) lines.push(`- 错误：${safeError}`)
  lines.push('')

  lines.push('## 问题（按严重度）', '')
  if (!parsed.issues.length) {
    lines.push('没有 ReviewIssue。', '')
  } else {
    const groups = new Map(SEV_META.map((m) => [m.key, []]))
    for (const item of parsed.issues) {
      const key = sevKey(item.severity)
      const bucket = groups.get(key) || groups.get('OTHER')
      bucket.push(item)
    }
    for (const m of SEV_META) {
      const items = groups.get(m.key) || []
      if (!items.length) continue
      lines.push(`### ${m.label}`, '')
      items.forEach((item, i) => {
        lines.push(`${i + 1}. \`${item.id}\` ${item.summary}${item.category ? ' · ' + item.category : ''}`)
        if (item.detail && item.detail !== item.summary) {
          lines.push(`   - ${clipExport(item.detail, 240)}`)
        }
      })
      lines.push('')
    }
  }

  const unverified = collectUnverifiedEvidence(artifacts)
  const issueUnverified = parsed.issues.filter((item) => {
    const blob = `${item.summary || ''} ${item.detail || ''} ${item.category || ''}`.toUpperCase()
    return item.severity === 'NOT_VERIFIED' || blob.includes('NOT_VERIFIED')
  })
  lines.push('## 引用未能核实（NOT_VERIFIED）', '')
  const nvRows = []
  const nvSeen = new Set()
  for (const row of unverified) {
    const key = `${row.id}|${row.claim}`
    if (!nvSeen.add(key)) continue
    nvRows.push(`\`${row.id || ''}\` ${row.claim || '未能核实'} · **NOT_VERIFIED**`)
  }
  for (const item of issueUnverified) {
    const key = `${item.id}|${item.summary}`
    if (!nvSeen.add(key)) continue
    nvRows.push(`\`${item.id}\` ${item.summary} · **NOT_VERIFIED**`)
  }
  if (!nvRows.length) {
    lines.push('没有标记为 NOT_VERIFIED 的引用。', '')
  } else {
    nvRows.forEach((row, i) => lines.push(`${i + 1}. ${row}`))
    lines.push('')
  }

  const human = parsed.tasks.filter((t) => t.kind === 'HUMAN_REQUIRED')
  const others = parsed.tasks.filter((t) => t.kind && t.kind !== 'HUMAN_REQUIRED')
  lines.push('## 需人工处理（HUMAN_REQUIRED）', '')
  if (!human.length && !others.length) {
    lines.push('没有 RevisionTask。', '')
  } else if (!human.length) {
    lines.push('没有 HUMAN_REQUIRED 项。', '')
  } else {
    human.forEach((t, i) => {
      const extra = t.issueId ? ` · 问题 \`${t.issueId}\`` : ''
      lines.push(`${i + 1}. \`${t.id || ''}\` ${t.instruction || ''}${extra}`)
    })
    lines.push('')
  }
  if (others.length) {
    lines.push('其他改稿计划：', '')
    for (const t of others) {
      const meta = KIND_META.find((m) => m.key === t.kind)
      lines.push(`- ${meta?.label || t.kind}：${t.instruction || ''}`)
    }
    lines.push('')
  }

  const patches = parsed.patches.filter(meaningfulPatch)
  lines.push('## 建议修改（RevisionPatch）', '')
  if (!patches.length) {
    lines.push('没有 RevisionPatch。', '')
  } else {
    patches.forEach((p, i) => {
      lines.push(`${i + 1}. \`${p.key || ''}\``)
      lines.push(`   - 原文：${clipExport(p.original, 180)}`)
      lines.push(`   - 建议：${clipExport(p.proposed, 180)}`)
      if (p.reason) lines.push(`   - 原因：${clipExport(p.reason, 180)}`)
    })
    lines.push('')
  }

  lines.push('## Artifact 原始 JSON', '')
  const exported = toExportArtifacts(artifacts)
  if (!exported.length) {
    lines.push('当前任务还没有 Artifact。', '')
  } else {
    exported.forEach((a, i) => {
      lines.push(`### ${i + 1}. ${AGENT_NAME[a.agent] || a.agent} · ${a.artifactType}`, '')
      lines.push('```json')
      lines.push(JSON.stringify(a.payload, null, 2))
      lines.push('```', '')
    })
  }
  lines.push('_本文件由当前任务已落库的 Artifact 汇总，不是单独的报告服务。_', '')
  return lines.join('\n')
}
