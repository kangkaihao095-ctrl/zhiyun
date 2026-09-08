/** 模型侧滑动窗口：最近 5 轮（最多 10 条）。只驻留前端内存，不写 localStorage / sessionStorage。 */
export const CS_MEMORY_ROUNDS = 5
export const CS_MEMORY_MAX_TURNS = CS_MEMORY_ROUNDS * 2

export function chatWindow(messages) {
  const list = Array.isArray(messages) ? messages : []
  const clean = []
  for (const row of list) {
    if (!row || (row.role !== 'user' && row.role !== 'assistant')) continue
    const content = String(row.content ?? '').trim()
    if (!content) continue
    clean.push({ role: row.role, content })
  }
  return clean.slice(-CS_MEMORY_MAX_TURNS)
}

export function chatRequestBody(messages) {
  return { messages: chatWindow(messages) }
}
