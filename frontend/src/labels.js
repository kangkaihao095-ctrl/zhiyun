export const CS_NAME = '云笺'

export function statusLabel(status) {
  return {
    PENDING: '排队中',
    RUNNING: '正在审校',
    WAITING_ACCEPT: '等你确认修改',
    DONE: '已完成',
    FAILED: '没能完成',
    PAID: '已到账',
    OFFICIAL: '正式稿',
    CANDIDATE: '候选稿',
    REJECTED: '未采纳'
  }[status] || status
}

export function versionStatusLabel(status) {
  return {
    OFFICIAL: '正式稿（主干）',
    CANDIDATE: '候选稿',
    REJECTED: '未采纳'
  }[status] || statusLabel(status)
}

export function formatTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function ledgerReason(reason) {
  if (reason === 'PURCHASE') return '充值到账'
  if (reason === 'REVIEW_USAGE' || reason === 'REVIEW_CONSUME') return '审校消耗'
  if (reason === 'SKILL_FEE') return '技能与提示词服务费'
  return reason || '账变'
}

export function ledgerRelated(refId) {
  const s = String(refId || '').trim()
  if (!s) return { label: '', to: '' }
  let m = s.match(/^task-(.+)$/i)
  if (m) return { label: `审校任务 ${m[1]}`, to: `/reviews/${m[1]}` }
  m = s.match(/^order-(.+)$/i)
  if (m) return { label: `订单 ${m[1]}`, to: `/orders/${m[1]}` }
  return { label: '', to: '' }
}

/** 流水页展示用：流水主键 + refId 里的订单号 / 任务号，不另造业务 ID。 */
export function ledgerIds(row) {
  const ref = String(row?.refId || '').trim()
  const order = ref.match(/^order-(.+)$/i)
  const task = ref.match(/^task-(.+)$/i)
  return {
    ledgerId: row?.id == null || row.id === '' ? '' : String(row.id),
    orderId: order ? order[1] : '',
    taskId: task ? task[1] : ''
  }
}

export async function copyText(text) {
  const value = String(text ?? '')
  if (!value) return false
  try {
    await navigator.clipboard.writeText(value)
    return true
  } catch {
    return false
  }
}

export function yuan(cents) {
  return ((cents || 0) / 100).toFixed(0)
}

export function quotaText(n) {
  return `${Number(n || 0)} 额度`
}

export function quotaHint(quota) {
  const n = Number(quota || 0)
  if (n <= 0) return '额度用完了，充值后才能继续检查。'
  const cite = n
  const full = Math.max(1, Math.floor(n / 7))
  if (n < 3) return `大约还能做 ${cite} 次引用核验。完整审校建议先充一些额度。`
  return `大约还能做 ${cite} 次引用核验，或 ${full} 次完整审校（按实际用量会有出入）。`
}
