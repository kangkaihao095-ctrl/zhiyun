import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { formatCsAnswer, matchesCsKeypoints } from '../src/cs-answer.js'
import { formatAssistant } from '../src/cs-format.js'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../eval/cs-eval.json')
const fixture = JSON.parse(readFileSync(root, 'utf8'))

function toolFor(row: { intent: string; toolResult?: Record<string, unknown> }) {
  const tool = { ...(row.toolResult || fixture.toolResult) }
  if (row.intent === 'plans') tool.plans = fixture.plans
  if (row.intent === 'task') Object.assign(tool, fixture.task, { task: fixture.task })
  return tool
}

describe('CsEval', () => {
  it('is a regression fixture, not an SLA', () => {
    expect(fixture.purpose).toBe('regression')
    expect(fixture.sla).toBe(false)
    expect(fixture.cases.length).toBeGreaterThanOrEqual(6)
  })

  it('formatCsAnswer follows keypoints and spent !== orders dump', () => {
    let spent = ''
    let orders = ''
    for (const row of fixture.cases) {
      const tool = toolFor(row)
      const answer = formatCsAnswer(row.intent, tool)
      expect(answer, row.id).toContain('\n')
      if (row.require_total_paid) {
        expect(answer).toContain(`¥${tool.totalPaidYuan}`)
      }
      for (const needle of row.must_contain || []) {
        expect(answer, `${row.id} ${needle}`).toContain(needle)
      }
      for (const needle of row.must_not_contain || []) {
        if (needle) expect(answer, row.id).not.toContain(needle)
      }
      expect(matchesCsKeypoints(row.intent, tool, answer)).toBe(true)
      if (row.intent === 'spent' && !spent) spent = answer
      if (row.intent === 'orders' && !orders) orders = answer
      const html = formatAssistant(answer)
      expect(html).toContain('<p>')
      expect(html).toMatch(/<(ul|ol|p)>/)
    }
    expect(spent).not.toBe(orders)
    expect(spent).not.toContain('订单号')
  })
})
