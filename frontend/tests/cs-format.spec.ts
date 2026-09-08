import { describe, expect, it } from 'vitest'
import { formatAssistant } from '../src/cs-format.js'

describe('formatAssistant', () => {
  it('renders short paragraphs and list items instead of one blob', () => {
    const html = formatAssistant('查过你的账户。\n\n- **订单号**：ZY1\n- **金额**：¥10\n- **状态**：PAID')
    expect(html).toContain('<p>')
    expect(html).toContain('<ul>')
    expect(html).toContain('<li>')
    expect(html).toContain('<strong>订单号</strong>')
    expect(html).not.toContain('<br>')
    expect(html.match(/<li>/g)?.length).toBe(3)
  })

  it('renders spend summary as paragraphs and a list, not one blob', () => {
    const html = formatAssistant('😊 先说合计：你已经支付的充值是 **¥10**。\n\n- **已支付充值**：¥10\n- **额度消耗**：12 额度')
    expect(html).toContain('<p>')
    expect(html).toContain('<ul>')
    expect(html).toContain('<li>')
    expect(html).toContain('<strong>已支付充值</strong>')
    expect((html.match(/<p>/g) || []).length).toBeGreaterThanOrEqual(1)
    expect((html.match(/<li>/g) || []).length).toBe(2)
  })

  it('keeps a missing-id notice on its own line', () => {
    const html = formatAssistant('本账户没有该 ID\n\n请再提供：\n- 任务 ID\n- 稿件 ID')
    expect(html).toContain('<p>本账户没有该 ID</p>')
    expect(html).toContain('<li>任务 ID</li>')
    expect(html).toContain('<li>稿件 ID</li>')
  })

  it('renders numbered lists', () => {
    const html = formatAssistant('1. 先给任务 ID\n2. 再查状态')
    expect(html).toContain('<ol>')
    expect(html).toContain('<li>先给任务 ID</li>')
  })
})
