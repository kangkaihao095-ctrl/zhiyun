/** Agent Trace 瀑布图：用 agent_span 的 startedAt / durationMs 算 left% / width%。不是 Jaeger 后端。 */

export type WaterfallInput = {
  agent?: string
  id?: string
  name?: string
  status?: string
  state?: string
  label?: string
  startedAt?: string | number | Date | null
  endedAt?: string | number | Date | null
  durationMs?: number | string | null
  tokens?: number | string | null
  checkpoint?: boolean
  skipped?: boolean
  fencingToken?: number | string | null
  errorCode?: string | null
  errorMessage?: string | null
  skillVersion?: string | null
  promptVersion?: string | null
  toolName?: string | null
  toolCalls?: unknown
  meta?: string
}

export type WaterfallBar = {
  agent: string
  name: string
  status: string
  state: string
  label: string
  startMs: number
  durationMs: number
  leftPct: number
  widthPct: number
  tokens: number | null
  checkpoint: boolean
  skipped: boolean
  fencingToken: number | null
  errorCode: string
  errorMessage: string
  skillVersion: string
  promptVersion: string
  toolName: string
  toolCount: number
  meta: string
}

export type WaterfallLayout = {
  totalMs: number
  axisTicks: string[]
  gridPcts: number[]
  bars: WaterfallBar[]
}

export function parseSpanTime(value: unknown): number | null {
  if (value == null || value === '') return null
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (value instanceof Date) {
    const t = value.getTime()
    return Number.isFinite(t) ? t : null
  }
  const t = Date.parse(String(value))
  return Number.isFinite(t) ? t : null
}

export function pctOf(part: number, total: number): number {
  if (!(total > 0) || !Number.isFinite(part)) return 0
  return Math.round((Math.max(0, part) / total) * 10000) / 100
}

export function formatAxisMs(ms: number): string {
  const n = Number(ms)
  if (!Number.isFinite(n) || n <= 0) return '0ms'
  if (n < 1000) return `${Math.round(n)}ms`
  const s = n / 1000
  return `${s >= 10 ? s.toFixed(0) : s.toFixed(1)}s`
}

function isRunning(span: WaterfallInput): boolean {
  const status = String(span.status || '')
  const state = String(span.state || '')
  return status === 'RUNNING' || state === 'current'
}

function readDuration(raw: unknown): number | null {
  if (raw == null || raw === '') return null
  const n = Number(raw)
  return Number.isFinite(n) ? Math.max(0, n) : null
}

export function toolCountOf(span: WaterfallInput | WaterfallBar | null | undefined): number {
  if (!span) return 0
  const calls = (span as WaterfallInput).toolCalls
  if (Array.isArray(calls)) return calls.length
  const n = Number(calls)
  if (Number.isFinite(n) && n >= 0) return Math.round(n)
  const named = Number((span as WaterfallBar).toolCount)
  if (Number.isFinite(named) && named >= 0) return named
  return String((span as WaterfallInput).toolName || '').trim() ? 1 : 0
}

function resolveDuration(
  span: WaterfallInput,
  startMs: number | null,
  endMs: number | null,
  nowMs: number
): number {
  const stored = readDuration(span.durationMs)
  if (stored != null) return stored
  if (startMs != null && endMs != null) return Math.max(0, endMs - startMs)
  if (isRunning(span) && startMs != null) return Math.max(0, nowMs - startMs)
  return 0
}

/**
 * 有 startedAt 的 span 相对最早开始时刻画条，重叠即并行并排。
 * 没有时间戳则按列表顺序累加 durationMs，仍然画条。
 */
export function layoutWaterfall(spans: WaterfallInput[], nowMs = Date.now()): WaterfallLayout {
  const list = Array.isArray(spans) ? spans : []
  const timed = list.map((span) => ({
    span,
    start: parseSpanTime(span.startedAt),
    end: parseSpanTime(span.endedAt)
  }))
  const origin = timed.reduce<number | null>((min, row) => {
    if (row.start == null) return min
    return min == null ? row.start : Math.min(min, row.start)
  }, null)

  const bars: WaterfallBar[] = []
  let cursor = 0
  for (const row of timed) {
    const span = row.span
    const durationMs = resolveDuration(span, row.start, row.end, nowMs)
    let startMs: number
    if (row.start != null && origin != null) {
      startMs = Math.max(0, row.start - origin)
    } else {
      startMs = cursor
    }
    cursor = Math.max(cursor, startMs + durationMs)
    const agent = String(span.agent || span.id || '')
    bars.push({
      agent,
      name: String(span.name || agent),
      status: String(span.status || ''),
      state: String(span.state || ''),
      label: String(span.label || ''),
      startMs,
      durationMs,
      leftPct: 0,
      widthPct: 0,
      tokens: readDuration(span.tokens),
      checkpoint: Boolean(span.checkpoint),
      skipped: Boolean(span.skipped),
      fencingToken: span.fencingToken == null || span.fencingToken === '' ? null : Number(span.fencingToken),
      errorCode: String(span.errorCode || ''),
      errorMessage: String(span.errorMessage || ''),
      skillVersion: String(span.skillVersion || ''),
      promptVersion: String(span.promptVersion || ''),
      toolName: String(span.toolName || ''),
      toolCount: toolCountOf(span),
      meta: String(span.meta || '')
    })
  }

  const totalMs = bars.reduce((max, bar) => Math.max(max, bar.startMs + bar.durationMs), 0)
  for (const bar of bars) {
    bar.leftPct = pctOf(bar.startMs, totalMs)
    bar.widthPct = pctOf(bar.durationMs, totalMs)
  }
  const gridPcts = [0, 25, 50, 75, 100]
  return {
    totalMs,
    axisTicks: gridPcts.map((p) => formatAxisMs(totalMs * (p / 100))),
    gridPcts,
    bars
  }
}
