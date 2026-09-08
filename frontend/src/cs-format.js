/** 云笺助手气泡：把 Markdown 短段落/列表渲染成 HTML，不把换行吃掉。 */

export function escapeHtml(text) {
  return String(text || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function inline(text) {
  return escapeHtml(text)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<span class="msg-code">$1</span>')
}

export function formatAssistant(text) {
  const raw = String(text || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  if (!raw.trim()) return ''
  const lines = raw.split('\n')
  const out = []
  let list = null

  function closeList() {
    if (!list) return
    out.push(`<${list.tag}>${list.items.join('')}</${list.tag}>`)
    list = null
  }

  for (const line of lines) {
    const ul = line.match(/^\s*[-*•]\s+(.+)$/)
    const ol = line.match(/^\s*\d+[.)]\s+(.+)$/)
    if (ul) {
      if (!list || list.tag !== 'ul') {
        closeList()
        list = { tag: 'ul', items: [] }
      }
      list.items.push(`<li>${inline(ul[1])}</li>`)
      continue
    }
    if (ol) {
      if (!list || list.tag !== 'ol') {
        closeList()
        list = { tag: 'ol', items: [] }
      }
      list.items.push(`<li>${inline(ol[1])}</li>`)
      continue
    }
    closeList()
    const trimmed = line.trim()
    if (!trimmed) continue
    const heading = trimmed.replace(/^#{1,6}\s+/, '')
    out.push(`<p>${inline(heading)}</p>`)
  }
  closeList()
  return out.join('')
}
