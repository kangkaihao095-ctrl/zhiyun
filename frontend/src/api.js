const base = '/api'

export function token() {
  return sessionStorage.getItem('token')
}

/** 浏览器把 CORS / 连接被拒都叫 Failed to fetch；HTTP 4xx 走另一条路径。 */
export function networkFailureMessage(err) {
  const raw = String(err && err.message ? err.message : err || '')
  if (/failed to fetch|networkerror|load failed|network request failed/i.test(raw)
      || (err && err.name === 'TypeError' && !raw)) {
    return '连不上服务器。请确认后端已在 http://127.0.0.1:8080 运行，并用 http://127.0.0.1:5173 打开页面。'
  }
  return raw || '请求失败'
}

function httpFailureMessage(status, bodyMsg, statusText) {
  if (bodyMsg) return bodyMsg
  if (status === 401 || status === 403) return '请先登录'
  if (status === 404) return '找不到这份结果'
  if (status === 400) return '请求无效'
  // Vite 在 8080 挂掉时仍返回 HTTP 500 Internal Server Error，不是 Failed to fetch。
  if (status === 502 || status === 503 || status === 504
      || (status >= 500 && /internal server error/i.test(statusText || ''))) {
    return networkFailureMessage(new TypeError('Failed to fetch'))
  }
  return statusText && !/failed to fetch/i.test(statusText)
    ? statusText
    : ('HTTP ' + status)
}

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (token()) {
    headers.Authorization = `Bearer ${token()}`
  }
  if (options.body && !(options.body instanceof FormData) && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json'
    options.body = JSON.stringify(options.body)
  }
  let res
  try {
    res = await fetch(base + path, { ...options, headers })
  } catch (err) {
    throw new Error(networkFailureMessage(err))
  }
  if (!res.ok) {
    let bodyMsg = ''
    try {
      const data = await res.json()
      bodyMsg = data.error || data.message || (typeof data === 'string' ? data : JSON.stringify(data))
    } catch {
      bodyMsg = ''
    }
    throw new Error(httpFailureMessage(res.status, bodyMsg, res.statusText))
  }
  if (res.status === 204) {
    return null
  }
  const ct = res.headers.get('content-type') || ''
  if (ct.includes('text/event-stream')) {
    return res
  }
  if (ct.startsWith('image/') || ct.includes('octet-stream')) {
    return res.blob()
  }
  try {
    return await res.json()
  } catch {
    throw new Error('服务器返回的不是可用结果')
  }
}

export function qs(params = {}) {
  const p = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    p.set(k, String(v))
  })
  const s = p.toString()
  return s ? '?' + s : ''
}

export const DEFAULT_PAGE_SIZE = 5

export function asPage(data) {
  if (Array.isArray(data)) {
    return { items: data, total: data.length, page: 1, size: data.length || DEFAULT_PAGE_SIZE }
  }
  const items = Array.isArray(data?.items) ? data.items : []
  return {
    items,
    total: Number(data?.total ?? items.length),
    page: Number(data?.page ?? 1),
    size: Number(data?.size ?? DEFAULT_PAGE_SIZE)
  }
}

export async function fetchAuthBlob(path) {
  let res
  try {
    res = await fetch(base + path, {
      headers: token() ? { Authorization: 'Bearer ' + token() } : {}
    })
  } catch (err) {
    throw new Error(networkFailureMessage(err))
  }
  if (!res.ok) {
    if (res.status === 401 || res.status === 403) throw new Error('请先登录')
    throw new Error('无法打开文件')
  }
  return res.blob()
}

export function sanitizeFilename(name) {
  const raw = String(name || '').trim() || 'zhiyun-review'
  return raw.replace(/[\\/:*?"<>|]/g, '_').slice(0, 180)
}

/**
 * 用 application/octet-stream 强制另存为。text/markdown 在 Safari / 部分 Chrome 会当成预览页，download 属性无效。
 * 异步 fetch 之后再 click，元素不能立刻从 DOM 拿掉，否则下载会被取消。
 */
export function downloadBlob(filename, blob) {
  if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
    throw new Error('当前浏览器无法保存文件')
  }
  if (!blob) {
    throw new Error('没有可下载的内容')
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = sanitizeFilename(filename)
  a.rel = 'noopener'
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  window.setTimeout(() => {
    a.remove()
    URL.revokeObjectURL(url)
  }, 2_000)
}

export function downloadText(filename, text, mime = 'application/octet-stream') {
  const blob = new Blob([String(text ?? '')], { type: mime })
  downloadBlob(filename, blob)
}

/** 把当前任务汇总 Markdown 存成文件，不另走报告服务。 */
export function downloadMarkdown(filename, markdown) {
  downloadText(filename || 'zhiyun-review.md', markdown, 'application/octet-stream')
}

export function downloadJson(filename, data) {
  const text = typeof data === 'string' ? data : JSON.stringify(data, null, 2)
  const name = String(filename || 'zhiyun-artifacts.json')
  downloadText(name.endsWith('.json') ? name : name + '.json', text, 'application/octet-stream')
}

export async function fetchAvatarBlobUrl() {
  if (!token()) return ''
  const res = await fetch(base + '/me/avatar', {
    headers: { Authorization: `Bearer ${token()}` }
  })
  if (!res.ok) return ''
  const blob = await res.blob()
  return URL.createObjectURL(blob)
}

