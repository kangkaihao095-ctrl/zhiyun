/** 用户任务页本次用量：口语化耗时 / token / 额度。不含瀑布图与工具名。 */

export type UserUsageNode = {
  name: string
  durationMs: number | null
  tokens: number | null
  quota: number | null
  skipped?: boolean
  status?: string
}

export type UserUsage = {
  durationMs: number
  tokens: number
  quota: number
  settled: boolean
  inProgress: boolean
  nodes: UserUsageNode[]
}

const NODE_KEYS = ['name', 'durationMs', 'tokens', 'quota', 'skipped', 'status'] as const
const ROOT_KEYS = ['durationMs', 'tokens', 'quota', 'settled', 'inProgress', 'nodes'] as const
const OPS_LEAK = /waterfall|toolName|errorCode|skillVersion|fencingToken|promptVersion|"lease"|MCP|errorCode/i

function numOrNull(raw: unknown): number | null {
  if (raw == null || raw === '') return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

function pickNode(raw: unknown): UserUsageNode {
  const row = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}
  return {
    name: typeof row.name === 'string' && row.name.trim() ? row.name : '审校环节',
    durationMs: numOrNull(row.durationMs),
    tokens: numOrNull(row.tokens),
    quota: numOrNull(row.quota),
    skipped: Boolean(row.skipped),
    status: typeof row.status === 'string' ? row.status : ''
  }
}

export function toUserUsage(raw: unknown): UserUsage {
  const row = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}
  const nodes = Array.isArray(row.nodes) ? row.nodes.map(pickNode) : []
  const inProgress = Boolean(row.inProgress)
  return {
    durationMs: numOrNull(row.durationMs) ?? 0,
    tokens: numOrNull(row.tokens) ?? 0,
    quota: numOrNull(row.quota) ?? 0,
    settled: Boolean(row.settled),
    inProgress,
    nodes: nodes.length || !inProgress ? nodes : [{
      name: '审校',
      durationMs: null,
      tokens: null,
      quota: null,
      skipped: false,
      status: 'RUNNING'
    }]
  }
}

export function usageHasOpsLeak(value: unknown): boolean {
  try {
    return OPS_LEAK.test(JSON.stringify(value))
  } catch {
    return true
  }
}

export function formatUsageSeconds(ms: number | null | undefined): string {
  if (ms == null || ms === ('' as unknown)) return ''
  const n = Number(ms)
  if (!Number.isFinite(n) || n <= 0) return ''
  if (n < 500) return '不到 1 秒'
  const s = Math.round(n / 1000)
  return `约 ${Math.max(1, s)} 秒`
}

export function usageNodeLine(node: UserUsageNode): string {
  const name = node.name || '审校环节'
  if (node.skipped) return `${name} 沿用上次结果，未再扣费`
  if (node.status === 'RUNNING' || (node.durationMs == null && node.tokens == null && node.status !== 'DONE' && node.status !== 'FAILED')) {
    return `${name} 进行中`
  }
  const parts = [name]
  const dur = formatUsageSeconds(node.durationMs)
  if (dur) parts.push(dur)
  if (node.tokens != null) parts.push(`${node.tokens} token`)
  if (node.quota != null) parts.push(`约 ${node.quota} 额度`)
  return parts.join(' · ')
}

export function usageSummaryLine(usage: UserUsage): string {
  if (!usage.nodes.length && usage.inProgress) return '本次审校进行中'
  const bits = ['本次']
  const dur = formatUsageSeconds(usage.durationMs)
  if (dur) bits.push(dur)
  else if (usage.inProgress) bits.push('进行中')
  bits.push(`${usage.tokens} token`)
  bits.push(usage.settled ? `已扣 ${usage.quota} 额度` : `预计 ${usage.quota} 额度`)
  if (usage.inProgress && dur) bits.push('进行中')
  return bits.join(' · ')
}

export function usageNodeRunning(node: UserUsageNode): boolean {
  if (node.skipped) return false
  if (node.status === 'RUNNING') return true
  if (node.status === 'DONE' || node.status === 'FAILED') return false
  return node.durationMs == null && node.tokens == null
}

export function splitUsageNodes(usage: UserUsage): { running: UserUsageNode[]; settled: UserUsageNode[] } {
  const running: UserUsageNode[] = []
  const settled: UserUsageNode[] = []
  for (const node of usage.nodes) {
    if (usageNodeRunning(node)) running.push(node)
    else settled.push(node)
  }
  return { running, settled }
}

export type UsageCols = {
  duration: string
  tokens: string
  quota: string
  inProgress: boolean
  settled: boolean
}

export function usageSummaryCols(usage: UserUsage): UsageCols {
  const dur = formatUsageSeconds(usage.durationMs)
  return {
    duration: dur || (usage.inProgress ? '进行中' : '—'),
    tokens: `${usage.tokens} token`,
    quota: usage.settled ? `已扣 ${usage.quota} 额度` : `预计 ${usage.quota} 额度`,
    inProgress: usage.inProgress,
    settled: usage.settled
  }
}

export type UsageNodeCols = {
  name: string
  duration: string
  tokens: string
  quota: string
}

export function usageNodeCols(node: UserUsageNode): UsageNodeCols {
  const name = node.name || '审校环节'
  if (node.skipped) {
    return { name, duration: '沿用上次', tokens: '—', quota: '未再扣费' }
  }
  if (usageNodeRunning(node)) {
    return { name, duration: '进行中', tokens: '—', quota: '—' }
  }
  return {
    name,
    duration: formatUsageSeconds(node.durationMs) || '—',
    tokens: node.tokens != null ? String(node.tokens) : '—',
    quota: node.quota != null ? `约 ${node.quota} 额度` : '—'
  }
}

export function usageLines(usage: UserUsage): string[] {
  if (!usage.nodes.length) {
    return usage.inProgress ? ['审校进行中'] : []
  }
  return usage.nodes.map(usageNodeLine)
}

export const USER_USAGE_SHAPE = { root: ROOT_KEYS, node: NODE_KEYS }
