import { describe, expect, it, vi } from 'vitest'
import { api, asPage, downloadJson, downloadMarkdown, fetchAuthBlob, networkFailureMessage, qs, sanitizeFilename, token } from '../src/api.js'

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { 'Content-Type': 'application/json', ...(init.headers as Record<string, string> | undefined) }
  })
}

describe('qs', () => {
  it('omits blank params', () => {
    expect(qs({ q: 'ACL', page: 2, size: '', empty: null })).toBe('?q=ACL&page=2')
    expect(qs({})).toBe('')
  })
})

describe('asPage', () => {
  it('wraps a bare array', () => {
    expect(asPage([1, 2])).toEqual({ items: [1, 2], total: 2, page: 1, size: 2 })
  })

  it('normalizes a paged payload', () => {
    expect(asPage({ items: [{ id: 1 }], total: 20, page: 2, size: 10 })).toEqual({
      items: [{ id: 1 }],
      total: 20,
      page: 2,
      size: 10
    })
  })

  it('treats missing items as empty', () => {
    expect(asPage({})).toEqual({ items: [], total: 0, page: 1, size: 5 })
  })
})

describe('api', () => {
  it('attaches the bearer token and parses JSON', async () => {
    sessionStorage.setItem('token', 'abc')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ quota: 3 }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(api('/me')).resolves.toEqual({ quota: 3 })
    expect(token()).toBe('abc')
    expect(fetchMock).toHaveBeenCalledWith('/api/me', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer abc' })
    }))
  })

  it('serializes JSON bodies', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    await api('/orders', { method: 'POST', body: { planId: 2 } })
    const options = fetchMock.mock.calls[0][1] as RequestInit
    expect(options.body).toBe(JSON.stringify({ planId: 2 }))
    expect((options.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('does not stringify FormData', async () => {
    const fd = new FormData()
    fd.append('file', new Blob(['x']), 'a.pdf')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ id: 1 }))
    vi.stubGlobal('fetch', fetchMock)
    await api('/manuscripts', { method: 'POST', body: fd })
    const options = fetchMock.mock.calls[0][1] as RequestInit
    expect(options.body).toBe(fd)
  })

  it('throws the server error message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ error: '额度不够了' }, { status: 409 })))
    await expect(api('/reviews')).rejects.toThrow('额度不够了')
  })

  it('maps Failed to fetch to a connection error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(api('/manuscripts/1/reviews', { method: 'POST' })).rejects.toThrow(/连不上服务器/)
  })

  it('maps Vite proxy 500 Internal Server Error to a connection error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('http proxy error', {
      status: 500,
      statusText: 'Internal Server Error',
      headers: { 'Content-Type': 'text/plain' }
    })))
    await expect(api('/auth/ops/login', { method: 'POST', body: { email: 'demo@zhiyun.dev' } }))
      .rejects.toThrow(/连不上服务器/)
  })

  it('keeps JSON 500 body instead of collapsing it to a connection error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ error: '观测台聚合失败' }, { status: 500 })))
    await expect(api('/ops/observability')).rejects.toThrow('观测台聚合失败')
  })

  it('surfaces HTTP 400/403 body instead of Failed to fetch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ error: '投稿期刊不在目录中' }, { status: 400 })))
    await expect(api('/manuscripts/1/reviews', { method: 'POST' })).rejects.toThrow('投稿期刊不在目录中')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ error: '无权操作' }, { status: 403 })))
    await expect(api('/manuscripts/1/reviews', { method: 'POST' })).rejects.toThrow('无权操作')
  })

  it('maps HTML 404 to a readable error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>not found</html>', {
      status: 404,
      headers: { 'Content-Type': 'text/html' }
    })))
    await expect(api('/reviews/9/report')).rejects.toThrow('找不到这份结果')
  })

  it('maps empty 401 to login', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
    await expect(api('/reviews/9/report')).rejects.toThrow('请先登录')
  })

  it('rejects non-JSON 200 bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>oops</html>', {
      status: 200,
      headers: { 'Content-Type': 'text/html' }
    })))
    await expect(api('/reviews/9/report')).rejects.toThrow('服务器返回的不是可用结果')
  })

  it('returns null on 204', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    await expect(api('/logout', { method: 'POST' })).resolves.toBeNull()
  })

  it('maps Failed to fetch without going through api()', () => {
    expect(networkFailureMessage(new TypeError('Failed to fetch'))).toMatch(/连不上服务器/)
    expect(networkFailureMessage(new Error('boom'))).toBe('boom')
  })

  it('returns the raw response for SSE', async () => {
    const res = new Response('data: hi\n\n', {
      headers: { 'Content-Type': 'text/event-stream' }
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res))
    await expect(api('/cs/chat', { method: 'POST' })).resolves.toBe(res)
  })
})

describe('fetchAuthBlob', () => {
  it('attaches the bearer token and returns a blob', async () => {
    sessionStorage.setItem('token', 'abc')
    const fetchMock = vi.fn().mockResolvedValue(new Response('pdf-bytes', {
      status: 200,
      headers: { 'Content-Type': 'application/pdf' }
    }))
    vi.stubGlobal('fetch', fetchMock)
    const file = await fetchAuthBlob('/manuscripts/1/versions/1/file')
    expect(file.size).toBe(9)
    expect(file.type).toContain('pdf')
    expect(fetchMock).toHaveBeenCalledWith('/api/manuscripts/1/versions/1/file', {
      headers: { Authorization: 'Bearer abc' }
    })
  })

  it('maps 401 to login and other failures to a file error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
    await expect(fetchAuthBlob('/manuscripts/1/versions/1/file')).rejects.toThrow('请先登录')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 404 })))
    await expect(fetchAuthBlob('/manuscripts/1/versions/1/file')).rejects.toThrow('无法打开文件')
  })

  it('maps Failed to fetch to a connection error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(fetchAuthBlob('/manuscripts/1/versions/1/file')).rejects.toThrow(/连不上服务器/)
  })
})

describe('downloadMarkdown', () => {
  function stubDownload() {
    const create = vi.fn().mockReturnValue('blob:review')
    const revoke = vi.fn()
    vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke })
    const click = vi.fn()
    const originalCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = originalCreate(tag)
      if (tag === 'a') el.click = click
      return el
    })
    return { create, click }
  }

  it('creates a file download with octet-stream so the browser saves instead of previewing', () => {
    const { create, click } = stubDownload()
    downloadMarkdown('zhiyun-review-9.md', '# 审校结果汇总\nNOT_VERIFIED')
    expect(create).toHaveBeenCalled()
    expect(click).toHaveBeenCalled()
    const blob = create.mock.calls[0][0] as Blob
    expect(blob.type).toContain('application/octet-stream')
    const a = document.body.querySelector('a[download]') as HTMLAnchorElement | null
    expect(a?.download).toBe('zhiyun-review-9.md')
  })

  it('still downloads when markdown is empty', () => {
    const { create, click } = stubDownload()
    downloadMarkdown('zhiyun-review-9.md', '')
    expect(create).toHaveBeenCalled()
    expect(click).toHaveBeenCalled()
  })

  it('throws when the browser cannot create object URLs', () => {
    vi.stubGlobal('URL', { createObjectURL: undefined, revokeObjectURL: vi.fn() })
    expect(() => downloadMarkdown('zhiyun-review-9.md', '# x')).toThrow('当前浏览器无法保存文件')
  })
})

describe('downloadJson / sanitizeFilename', () => {
  it('strips illegal filename characters', () => {
    expect(sanitizeFilename('zhiyun/review:9.md')).toBe('zhiyun_review_9.md')
    expect(sanitizeFilename('')).toBe('zhiyun-review')
  })

  it('serializes artifacts json', () => {
    const create = vi.fn().mockReturnValue('blob:json')
    vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: vi.fn() })
    const click = vi.fn()
    const originalCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = originalCreate(tag)
      if (tag === 'a') el.click = click
      return el
    })
    downloadJson('zhiyun-artifacts-9.json', { taskId: 9, artifacts: [{ artifactType: 'ReviewIssue' }] })
    expect(click).toHaveBeenCalled()
    const blob = create.mock.calls[0][0] as Blob
    expect(blob.type).toContain('application/octet-stream')
  })
})
