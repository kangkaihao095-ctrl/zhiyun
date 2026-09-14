import { describe, expect, it } from 'vitest'
import { layoutWaterfall, pctOf, formatAxisMs, toolCountOf } from '../src/trace-waterfall'

describe('layoutWaterfall', () => {
  it('places overlapping startedAt spans side by side on the time axis', () => {
    const layout = layoutWaterfall([
      {
        agent: 'CITATION_INTEGRITY',
        name: '引用核验',
        startedAt: '2026-01-01T00:00:00.000Z',
        durationMs: 100
      },
      {
        agent: 'FIGURE_PDF',
        name: '图表检查',
        startedAt: '2026-01-01T00:00:00.020Z',
        durationMs: 80
      }
    ])
    expect(layout.totalMs).toBe(100)
    expect(layout.bars[0]).toMatchObject({ leftPct: 0, widthPct: 100, startMs: 0, durationMs: 100 })
    expect(layout.bars[1]).toMatchObject({ leftPct: 20, widthPct: 80, startMs: 20, durationMs: 80 })
    expect(layout.axisTicks).toEqual(['0ms', '25ms', '50ms', '75ms', '100ms'])
    expect(formatAxisMs(1500)).toBe('1.5s')
  })

  it('accumulates durationMs in order when startedAt is missing', () => {
    const layout = layoutWaterfall([
      { agent: 'A', durationMs: 100 },
      { agent: 'B', durationMs: 50 }
    ])
    expect(layout.totalMs).toBe(150)
    expect(layout.bars[0]).toMatchObject({ leftPct: 0, widthPct: pctOf(100, 150) })
    expect(layout.bars[1]).toMatchObject({ leftPct: pctOf(100, 150), widthPct: pctOf(50, 150) })
    expect(layout.bars[0].leftPct + layout.bars[0].widthPct).toBe(layout.bars[1].leftPct)
  })

  it('derives duration from startedAt/endedAt and keeps skipped bars at 0 width', () => {
    const layout = layoutWaterfall([
      {
        agent: 'A',
        startedAt: '2026-01-01T00:00:00.000Z',
        endedAt: '2026-01-01T00:00:00.040Z'
      },
      {
        agent: 'B',
        startedAt: '2026-01-01T00:00:00.040Z',
        durationMs: 0,
        skipped: true,
        checkpoint: true
      }
    ])
    expect(layout.totalMs).toBe(40)
    expect(layout.bars[0]).toMatchObject({ durationMs: 40, leftPct: 0, widthPct: 100 })
    expect(layout.bars[1]).toMatchObject({ durationMs: 0, leftPct: 100, widthPct: 0, skipped: true })
  })

  it('uses nowMs for a running span that has startedAt but no duration', () => {
    const layout = layoutWaterfall(
      [{
        agent: 'CITATION_INTEGRITY',
        state: 'current',
        startedAt: '2026-01-01T00:00:00.000Z'
      }],
      Date.parse('2026-01-01T00:00:00.250Z')
    )
    expect(layout.totalMs).toBe(250)
    expect(layout.bars[0]).toMatchObject({ leftPct: 0, widthPct: 100, durationMs: 250 })
  })

  it('counts toolCalls for the span tooltip', () => {
    const layout = layoutWaterfall([{
      agent: 'CITATION_INTEGRITY',
      toolName: 'AcademicSearchTool',
      toolCalls: [{ name: 'AcademicSearchTool' }, { name: 'lookupDoi' }],
      durationMs: 40
    }])
    expect(layout.bars[0].toolCount).toBe(2)
    expect(toolCountOf({ toolName: 'AcademicSearchTool' })).toBe(1)
    expect(toolCountOf({ toolCalls: [] })).toBe(0)
  })
})
