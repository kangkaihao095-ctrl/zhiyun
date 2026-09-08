/** 客服标准作答结构。数字必须来自 Tool JSON，禁止让模型口算。 */

function num(tool, key) {
  const raw = tool?.[key]
  if (typeof raw === 'number') return Math.trunc(raw)
  if (raw == null) return 0
  const n = Number(String(raw).replace('¥', '').trim())
  return Number.isFinite(n) ? Math.trunc(n) : 0
}

function blank(raw) {
  if (raw == null) return '—'
  const text = String(raw).trim()
  return text && text !== 'null' ? text : '—'
}

export function formatCsAnswer(intent, toolResult) {
  const kind = String(intent || '').trim().toLowerCase()
  const tool = toolResult || {}
  switch (kind) {
    case 'spent':
      return formatSpent(tool)
    case 'orders':
      return formatOrders(tool)
    case 'quota':
      return formatQuota(tool)
    case 'task':
      return formatTask(tool)
    case 'missing_id':
      return formatMissing()
    case 'plans':
      return formatPlans(tool)
    case 'howto_models':
      return formatHowToModels()
    default:
      return ''
  }
}

function formatSpent(tool) {
  const paid = num(tool, 'totalPaidYuan')
  const pending = num(tool, 'totalPendingYuan')
  const consumed = num(tool, 'quotaConsumed')
  const balance = num(tool, 'quotaBalance')
  return [
    `😊 先说合计：你已经支付的充值是 **¥${paid}**，额度消耗是另一笔账。`,
    '',
    `- **已支付充值**：¥${paid}（仅 PAID；¥0 演示单不计入）`,
    `- **额度消耗**：${consumed} 额度（流水扣减，不是订单）`,
    `- **当前余额**：${balance} 额度`,
    '',
    `待支付 ¥${pending} 尚未计入「花了多少钱」。订单在本产品里是充值单，不能当成消费清单。`
  ].join('\n')
}

function formatOrders(tool) {
  if (tool.range && typeof tool.range === 'object') return formatRangedOrders(tool)
  const paid = num(tool, 'totalPaidYuan')
  const pending = num(tool, 'totalPendingYuan')
  const consumed = num(tool, 'quotaConsumed')
  const balance = num(tool, 'quotaBalance')
  const recharge = num(tool, 'rechargeCount')
  const total = num(tool, 'totalOrders')
  const limit = Math.max(1, num(tool, 'recentLimit') || 5)
  const orders = Array.isArray(tool.orders) ? tool.orders : []
  const lines = [
    '📋 订单在本产品里是**充值单**，不是消费清单。',
    '',
    `- **已支付充值合计**：¥${paid}（${recharge} 笔已支付）`,
    `- **额度消耗**：${consumed} 额度`,
    `- **当前余额**：${balance} 额度`,
    `- **待支付**：¥${pending}`
  ]
  if (!orders.length) {
    lines.push('', '本账户没有充值订单。')
    return lines.join('\n')
  }
  const shown = Math.min(limit, orders.length)
  lines.push('')
  if (total > shown) lines.push(`共 ${total} 笔，这里只列最近 ${shown} 笔：`)
  else lines.push('最近充值：')
  for (const order of orders.slice(0, shown)) {
    lines.push(orderLine(order))
  }
  return lines.join('\n')
}

function formatRangedOrders(tool) {
  const from = tool.range?.from
  const to = tool.range?.to
  const orders = Array.isArray(tool.orders) ? tool.orders : []
  let matched = num(tool, 'matchedCount')
  if (!matched) matched = orders.length
  const paid = num(tool, 'totalPaidYuan')
  const pending = num(tool, 'totalPendingYuan')
  const lines = [
    `📋 已按 **${from}～${to}**（Asia/Shanghai）查过本账户订单。`,
    '',
    `- **查询范围**：${from} 至 ${to}`,
    `- **命中**：${matched} 笔`,
    `- **范围内已支付充值**：¥${paid}`,
    `- **范围内待支付**：¥${pending}`
  ]
  if (!orders.length || matched === 0) {
    const twoDays = from && to && from !== to
    lines.push('', twoDays ? '这两天没有订单。' : '该日期范围内没有订单。')
    return lines.join('\n')
  }
  lines.push('', '范围内订单：')
  for (const order of orders) lines.push(orderLine(order))
  return lines.join('\n')
}

function orderLine(order) {
  const yuan = order.amountYuan != null ? num(order, 'amountYuan') : Math.trunc(num(order, 'amountCents') / 100)
  const demo = order.demo === true ? ' · 演示单' : ''
  return `- **订单号**：${order.orderNo} · ¥${yuan} · ${blank(order.status)}${demo} · ${blank(order.createdAt)}`
}

function formatQuota(tool) {
  const balance = num(tool, 'quotaBalance') || num(tool, 'balance')
  const consumed = num(tool, 'quotaConsumed')
  return [
    `😊 查过你的账户，现在还剩 **${balance}** 额度。`,
    '',
    '- 这是账户余额，不是套餐里的标称额度',
    `- **已消耗**：${consumed} 额度（流水扣减）`
  ].join('\n')
}

function formatTask(tool) {
  const row = tool.task && typeof tool.task === 'object' ? tool.task : tool
  const id = row.taskId ?? tool.taskId
  const lines = [`📋 已按本账户查过${id != null ? ` ID **${id}**` : ''}。`, '']
  lines.push(`- **任务 ID**：${blank(row.taskId ?? tool.taskId)}`)
  lines.push(`- **状态**：${blank(row.status ?? tool.status)}`)
  const workflow = row.workflow ?? tool.workflow
  if (workflow) lines.push(`- **流程**：${workflow}`)
  const venue = row.targetVenue ?? tool.targetVenue
  if (venue) lines.push(`- **投稿目标**：${venue}`)
  const ms = row.manuscriptId ?? tool.manuscriptId
  if (ms != null) lines.push(`- **稿件 ID**：${ms}`)
  const title = row.manuscriptTitle ?? tool.manuscriptTitle
  if (title) lines.push(`- **稿件标题**：${title}`)
  return lines.join('\n').trim()
}

function formatMissing() {
  return ['本账户没有该 ID', '', '请再提供：', '- 任务 ID', '- 稿件 ID', '- 订单号'].join('\n')
}

function formatPlans(tool) {
  const plans = Array.isArray(tool.plans) ? tool.plans : []
  const lines = [
    '✨ 套餐规则可以按知识库说明来买；**你账户要付多少钱以套餐表 / Tool 为准**。',
    '',
    '打开「充值」页选套餐或输入金额（灵活充值最低 10 元，1 元 1 额度），确认支付后额度到账。'
  ]
  if (plans.length) {
    lines.push('', '套餐标价如下（不是你的当前余额）：')
    for (const plan of plans) {
      lines.push(`- **${blank(plan.name)}**：${plan.quotaAmount ?? plan.quota} 额度 / ¥${plan.priceYuan ?? plan.price}`)
    }
  }
  return lines.join('\n')
}

function formatHowToModels() {
  return [
    '🔧 论文模型在 **设置 → 模型配置**（`/account?panel=models`）里改。',
    '',
    '操作步骤：',
    '1. 打开左侧 **设置**',
    '2. 点 **模型配置**',
    '3. 点开某一个论文 Agent，选提供商，填写 Base URL 与 API Key',
    '4. 点 **保存**',
    '',
    '- 论文 7 个 Agent 可填你自己的 OpenAI 兼容 Key；平台只收技能费',
    '- **云笺**和 **RAG 检索**仍走平台模型，不能换',
    '- 语言润色（Style）不授予文献检索（AcademicSearch）',
    '- 没有给开发者的通用审校 API Key，也不走商务定制开放 API',
    '',
    '额度在右上角，充值点旁边的充值按钮会弹出窗口。'
  ].join('\n')
}

export function matchesCsKeypoints(intent, toolResult, answer) {
  const text = String(answer || '')
  const paid = `¥${num(toolResult, 'totalPaidYuan')}`
  if (intent === 'spent') {
    return text.includes(paid) && text.includes('额度') && !text.includes('订单号')
  }
  if (intent === 'orders') {
    if (toolResult?.range) {
      const from = String(toolResult.range.from || '')
      const to = String(toolResult.range.to || '')
      return text.includes(from) && text.includes(to) && !text.includes('最近')
    }
    return text.includes(paid) && text.includes('额度') && text !== formatCsAnswer('spent', toolResult)
  }
  if (intent === 'quota') {
    return text.includes(String(num(toolResult, 'quotaBalance'))) && text.includes('额度')
  }
  if (intent === 'missing_id') return text.includes('本账户没有该 ID')
  if (intent === 'plans') return text.includes('套餐') && text.includes('¥')
  if (intent === 'task') return text.includes('任务') && text.includes('\n')
  if (intent === 'howto_models') {
    const banned = text.includes('联系商务') || text.includes('通用 API Key 未上线')
    const path = (text.includes('设置') && text.includes('模型配置')) || text.includes('/account?panel=models')
    return path && !banned
  }
  return true
}
