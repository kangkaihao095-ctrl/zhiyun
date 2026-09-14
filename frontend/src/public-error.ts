export type PublicErrorCode =
  | 'cancelled'
  | 'arrearage'
  | 'invalid_key'
  | 'timeout'
  | 'structured_output'
  | 'network'
  | 'fencing'
  | 'unknown'

export const PUBLIC_ERROR_COPY: Record<PublicErrorCode, string> = {
  cancelled: '已取消',
  arrearage: '平台模型额度不足，请稍后再试。',
  invalid_key: '模型密钥无效或已过期。',
  timeout: '审校超时，请从检查点继续。',
  structured_output: '模型输出格式校验失败，请重试。',
  network: '网络异常，请稍后重试。',
  fencing: '审校没能完成，请稍后重试。',
  unknown: '审校没能完成，请稍后重试。'
}

/** 看板失败码短标签，不是 SLA 名。 */
export const ERROR_CODE_LABEL: Record<PublicErrorCode, string> = {
  cancelled: '已取消',
  arrearage: '额度不足',
  invalid_key: '密钥无效',
  timeout: '超时',
  structured_output: '输出校验失败',
  network: '网络异常',
  fencing: 'fencing 拒绝',
  unknown: '其他'
}

export function errorCodeLabel(code: unknown): string {
  const key = String(code || '') as PublicErrorCode
  return ERROR_CODE_LABEL[key] || (key ? key : '其他')
}

const COPY_TO_CODE = new Map(
  (Object.entries(PUBLIC_ERROR_COPY) as Array<[PublicErrorCode, string]>).map(([code, text]) => [text, code])
)

function blob(raw: unknown): string {
  if (raw == null) return ''
  return String(raw)
}

export function classifyPublicError(raw: unknown): PublicErrorCode {
  const text = blob(raw).trim()
  if (!text) return 'unknown'
  const known = COPY_TO_CODE.get(text)
  if (known) return known

  const lower = text.toLowerCase()

  if (text === '已取消' || lower === 'cancelled' || lower === 'canceled') {
    return 'cancelled'
  }
  if (
    /arrearage|overdue-payment|overdue_payment|access denied|good standing|insufficient.?quota|quota.?exceed|billing|欠费|余额不足|额度不足/.test(lower)
    || /arrearage/.test(text)
  ) {
    return 'arrearage'
  }
  if (
    /invalid.?api.?key|incorrect.?api.?key|authentication|unauthorized|invalid_api_key|401\b|密钥无效|api key/.test(lower)
  ) {
    return 'invalid_key'
  }
  if (/timeout|timed out|time-out|超时/.test(lower)) {
    return 'timeout'
  }
  if (/fencing/.test(lower)) {
    return 'fencing'
  }
  if (/structured output|schema validation|illegal output/.test(lower)) {
    return 'structured_output'
  }
  if (
    /econnrefused|enotfound|socket|unknownhost|connection refused|network|bad gateway|502\b|503\b|504\b/.test(lower)
  ) {
    return 'network'
  }
  return 'unknown'
}

export function publicErrorMessage(raw: unknown): string {
  const text = blob(raw).trim()
  if (!text) return ''
  return PUBLIC_ERROR_COPY[classifyPublicError(text)]
}

export function publicErrorCode(raw: unknown): PublicErrorCode | '' {
  const text = blob(raw).trim()
  if (!text) return ''
  return classifyPublicError(text)
}

/** 给演示折叠区用：只给内部码，不含 JSON / URL / 账单原文。 */
export function publicErrorTech(raw: unknown): string {
  const code = publicErrorCode(raw)
  return code ? `内部码：${code}` : ''
}

export function isUnsafeErrorDetail(raw: unknown): boolean {
  const text = blob(raw)
  if (!text) return false
  return /https?:\/\/|request_id|help\.aliyun|"error"\s*:|arrearage|overdue-payment|\{/.test(text)
}
