import { describe, expect, it } from 'vitest'
import {
  toUserUsage,
  usageHasOpsLeak,
  usageNodeCols,
  usageNodeLine,
  usageSummaryCols,
  usageSummaryLine,
  usageLines,
  splitUsageNodes
} from '../src/task-usage'

describe('user task usage', () => {
  it('keeps name/duration/tokens/quota and drops waterfall/toolName', () => {
    const usage = toUserUsage({
      durationMs: 12000,
      tokens: 820,
      quota: 3,
      settled: true,
      inProgress: false,
      waterfall: [{ toolName: 'AcademicSearchTool' }],
      skillVersion: '1',
      errorCode: 'timeout',
      nodes: [{
        name: '引用核验',
        durationMs: 12000,
        tokens: 820,
        quota: 3,
        skipped: false,
        status: 'DONE',
        toolName: 'AcademicSearchTool',
        waterfall: [{ width: 40 }],
        errorCode: 'timeout',
        skillVersion: '1'
      }]
    })
    expect(Object.keys(usage.nodes[0])).toEqual(['name', 'durationMs', 'tokens', 'quota', 'skipped', 'status'])
    expect(usage.nodes[0]).not.toHaveProperty('waterfall')
    expect(usage.nodes[0]).not.toHaveProperty('toolName')
    expect(JSON.stringify(usage)).not.toMatch(/waterfall|toolName/)
    expect(usageHasOpsLeak(usage)).toBe(false)
    expect(usageNodeLine(usage.nodes[0])).toBe('引用核验 · 约 12 秒 · 820 token · 约 3 额度')
    expect(usageSummaryLine(usage)).toBe('本次 · 约 12 秒 · 820 token · 已扣 3 额度')
    expect(usageSummaryCols(usage)).toEqual({
      duration: '约 12 秒',
      tokens: '820 token',
      quota: '已扣 3 额度',
      inProgress: false,
      settled: true
    })
    expect(splitUsageNodes(usage).settled).toHaveLength(1)
    expect(splitUsageNodes(usage).running).toHaveLength(0)
    expect(usageNodeCols(usage.nodes[0])).toEqual({
      name: '引用核验',
      duration: '约 12 秒',
      tokens: '820',
      quota: '约 3 额度'
    })
  })

  it('says skip was reused without charging again', () => {
    const line = usageNodeLine({
      name: '图表检查',
      durationMs: 0,
      tokens: 0,
      quota: 0,
      skipped: true,
      status: 'DONE'
    })
    expect(line).toBe('图表检查 沿用上次结果，未再扣费')
    expect(line).not.toMatch(/errorCode|skillVersion|MCP|waterfall|toolName/)
  })

  it('keeps an in-progress placeholder so the block is not blank', () => {
    const usage = toUserUsage({
      durationMs: 8000,
      tokens: 400,
      quota: 1,
      settled: false,
      inProgress: true,
      nodes: [
        { name: '引用核验', durationMs: 8000, tokens: 400, quota: 1, skipped: false, status: 'DONE' },
        { name: '语言润色', durationMs: null, tokens: null, quota: null, status: 'RUNNING' }
      ]
    })
    expect(usageLines(usage)).toEqual([
      '引用核验 · 约 8 秒 · 400 token · 约 1 额度',
      '语言润色 进行中'
    ])
    expect(usageSummaryLine(usage)).toContain('预计 1 额度')
    expect(usageSummaryLine(usage)).toContain('进行中')
    expect(usageSummaryCols(usage).quota).toBe('预计 1 额度')
    expect(usageSummaryCols(usage).inProgress).toBe(true)
    expect(splitUsageNodes(usage).running.map((n) => n.name)).toEqual(['语言润色'])
    expect(splitUsageNodes(usage).settled.map((n) => n.name)).toEqual(['引用核验'])
    expect(usageNodeCols(usage.nodes[1]).duration).toBe('进行中')
    expect(JSON.stringify(usage)).not.toMatch(/waterfall|toolName/)
  })
})
