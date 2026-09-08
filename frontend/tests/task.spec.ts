import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.fn()
const downloadMarkdown = vi.fn()
const downloadJson = vi.fn()
vi.mock('../src/api.js', () => ({
  api: (...args: unknown[]) => api(...args),
  downloadMarkdown: (...args: unknown[]) => downloadMarkdown(...args),
  downloadJson: (...args: unknown[]) => downloadJson(...args),
  fetchAuthBlob: vi.fn()
}))

import Task from '../src/views/Task.vue'

const Blank = defineComponent({ template: '<div />' })

const failedTask = {
  id: 9,
  status: 'FAILED',
  workflow: 'FULL_REVIEW',
  checkpointAgent: 'CITATION_INTEGRITY',
  fencingToken: 3,
  errorMessage: 'structured output failed after retry',
  manuscriptId: 3,
  sourceVersion: 1,
  createdAt: '2026-09-07T03:00:00+08:00'
}

function jsonFor(path: string) {
  if (path === '/workflows') {
    return { workflows: [{ id: 'FULL_REVIEW', name: '投稿前完整审校', capPoints: 10 }], billing: { tokensPerPoint: 2000 } }
  }
  if (path === '/me') return { quota: 3 }
  if (path === '/reviews/9') return failedTask
  if (path === '/reviews/9/artifacts') {
    return [{
      id: 41,
      agent: 'REVISION_PLANNING',
      artifactType: 'RevisionTask',
      payload: JSON.stringify({
        body: [
          { taskId: 'rt-h', issueId: 'iss-h', kind: 'HUMAN_REQUIRED', instruction: '核对幽灵引用' },
          { taskId: 'rt-a', issueId: 'iss-a', kind: 'AI_AUTOMATABLE', instruction: '去掉套话' }
        ]
      })
    }, {
      id: 42,
      agent: 'CITATION_INTEGRITY',
      artifactType: 'ReviewIssue',
      payload: JSON.stringify({
        body: [{
          issueId: 'iss-h',
          severity: 'HIGH',
          category: 'CITATION',
          section: 'references',
          location: { anchor: '10.0000/ghost.doi' },
          summary: 'DOI 10.0000/ghost.doi not found',
          detail: 'Java metadata verification failed; model is forbidden to invent a replacement DOI.',
          originalText: 'see 10.0000/ghost.doi',
          evidenceIds: ['ev-ghost']
        }]
      })
    }, {
      id: 43,
      agent: 'CITATION_INTEGRITY',
      artifactType: 'Evidence',
      payload: JSON.stringify({
        body: [{
          evidenceId: 'ev-ghost',
          source: 'CROSSREF',
          status: 'NOT_VERIFIED',
          claim: 'Citation 10.0000/ghost.doi',
          excerpt: '10.0000/ghost.doi'
        }]
      })
    }]
  }
  if (path === '/reviews/9/diff') return { official: 'body', candidate: 'body' }
  if (path === '/reviews/9/retry') return { ...failedTask, status: 'PENDING', errorMessage: null }
  if (path === '/reviews/9/report') {
    return { taskId: 9, filename: 'zhiyun-review-9.md', markdown: '# 审校结果汇总\n\n- **NOT_VERIFIED**\n' }
  }
  if (path === '/manuscripts/3/versions/1/inspect') {
    return { manuscriptId: 3, versionNo: 1, format: 'MD', pagePreview: false }
  }
  if (path.startsWith('/reviews/9/trace')) {
    return {
      taskId: 9,
      workflow: 'FULL_REVIEW',
      checkpointAgent: 'CITATION_INTEGRITY',
      fencingToken: 3,
      nodes: [{
        agent: 'CITATION_INTEGRITY',
        name: '引用核验',
        status: 'DONE',
        durationMs: 1200,
        tokens: 2000,
        fencingToken: 3,
        checkpoint: true
      }]
    }
  }
  if (path.startsWith('/inbox')) return { items: [], unreadCount: 0 }
  return {}
}

async function mountTask() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/reviews/:id', component: Task },
      { path: '/history', component: Blank },
      { path: '/manuscripts/:id', component: Blank },
      { path: '/billing', component: Blank }
    ]
  })
  await router.push('/reviews/9')
  await router.isReady()
  const refreshMe = vi.fn().mockResolvedValue(undefined)
  const wrapper = mount(Task, {
    global: {
      plugins: [router],
      provide: { refreshMe }
    }
  })
  await flushPromises()
  return { wrapper, refreshMe }
}

describe('Task result page', () => {
  beforeEach(() => {
    api.mockReset()
    downloadMarkdown.mockReset()
    downloadJson.mockReset()
    api.mockImplementation((path: string) => Promise.resolve(jsonFor(path)))
  })

  it('shows checkpoint pipeline, failed node error, retry, and HUMAN_REQUIRED group', async () => {
    const { wrapper } = await mountTask()
    const text = wrapper.text()
    expect(text).toContain('投稿前完整审校 · Agent Trace')
    expect(text).toContain('完成')
    expect(text).toContain('引用核验')
    expect(text).toContain('1.2 s')
    expect(text).toContain('2000 token')
    expect(text).toContain('fence 3')
    expect(text).toContain('fencing 3')
    expect(text).toContain('checkpoint')
    expect(text).toContain('失败')
    expect(text).toContain('图表检查')
    expect(text).toContain('未到')
    expect(text).toContain('模型输出格式校验失败，请重试。')
    expect(text).not.toContain('structured output failed after retry')
    expect(text).toContain('技术详情')
    expect(wrapper.find('.tr-tech').attributes('open')).toBeUndefined()
    expect(text).toContain('从检查点继续')
    expect(text).toContain('需要人工处理')
    expect(text).toContain('核对幽灵引用')
    expect(text).toContain('可由系统改')
    expect(text).toContain('任务 ID')
    expect(text).toContain('为什么高')
    expect(text).toContain('会改引用结论与作者责任')
    expect(text).toContain('建议怎么改')
    expect(text).toContain('定位')
    expect(text).toContain('规则依据')
    expect(text).toContain('Evidence ev-ghost')
    await wrapper.get('.tr-summary').trigger('click')
    expect(wrapper.text()).toContain('为什么高')
    expect(wrapper.text()).toContain('see 10.0000/ghost.doi')
    expect(text).toContain('导出 Artifact')
    expect(text).toContain('导出汇总')
    expect(text).not.toContain('PDF 页与图表')
    await wrapper.get('.tr-retry button').trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/reviews/9/retry', { method: 'POST' })
    wrapper.unmount()
  })

  it('marks the opened task inbox read so the sidebar badge can drop', async () => {
    const { wrapper, refreshMe } = await mountTask()
    expect(api).toHaveBeenCalledWith('/inbox/read', { method: 'POST', body: { refId: 'task-9' } })
    expect(refreshMe).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not dump Aliyun arrearage JSON to the user', async () => {
    const raw = 'structured output failed after retry: 400 Bad Request: "{"error":{"message":"Access denied, please make sure your account is in good standing. For details, see: https://help.aliyun.com/zh/model-studio/error-code#overdue-payment","type":"Arrearage","code":"Arrearage"},"request_id":"18bf2a2c"}"'
    api.mockImplementation((path: string) => {
      if (path === '/reviews/9') return Promise.resolve({ ...failedTask, errorMessage: raw })
      if (path.startsWith('/reviews/9/trace')) {
        return Promise.resolve({
          taskId: 9,
          workflow: 'FULL_REVIEW',
          checkpointAgent: 'CITATION_INTEGRITY',
          fencingToken: 3,
          nodes: [{
            agent: 'FIGURE_PDF',
            name: '图表检查',
            status: 'FAILED',
            durationMs: 5100,
            fencingToken: 5,
            errorMessage: raw
          }]
        })
      }
      return Promise.resolve(jsonFor(path))
    })
    const { wrapper } = await mountTask()
    const text = wrapper.text()
    expect(text).toContain('平台模型额度不足，请稍后再试。')
    expect(text).not.toContain('help.aliyun')
    expect(text).not.toContain('request_id')
    expect(text).not.toContain('overdue-payment')
    expect(wrapper.find('.tr-tech').exists()).toBe(true)
    expect(wrapper.find('.tr-tech').attributes('open')).toBeUndefined()
    wrapper.unmount()
  })

  it('exports the current-task artifact summary', async () => {
    const { wrapper } = await mountTask()
    await wrapper.get('.tr-export button:nth-child(2)').trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/reviews/9/report')
    expect(downloadMarkdown).toHaveBeenCalledWith(
      'zhiyun-review-9.md',
      expect.stringContaining('NOT_VERIFIED')
    )
    wrapper.unmount()
  })

  it('exports artifact JSON even when /report is missing', async () => {
    api.mockImplementation((path: string) => {
      if (path === '/reviews/9/report') return Promise.reject(new Error('找不到这份结果'))
      return Promise.resolve(jsonFor(path))
    })
    const { wrapper } = await mountTask()
    expect(wrapper.text()).toContain('按条下载 JSON')
    await wrapper.get('.tr-export button').trigger('click')
    await flushPromises()
    expect(downloadJson).toHaveBeenCalled()
    const payload = downloadJson.mock.calls[0][1] as { artifacts: Array<{ artifactType: string }>, markdown: string }
    expect(downloadJson.mock.calls[0][0]).toBe('zhiyun-artifacts-9.json')
    expect(payload.artifacts.some((a) => a.artifactType === 'ReviewIssue')).toBe(true)
    expect(payload.markdown).toContain('NOT_VERIFIED')
    expect(wrapper.text()).not.toContain('找不到这份结果')
    wrapper.unmount()
  })

  it('downloads a single artifact JSON from the result page', async () => {
    const { wrapper } = await mountTask()
    await wrapper.get('.tr-art-list summary').trigger('click')
    await wrapper.get('.tr-art-item').trigger('click')
    expect(downloadJson).toHaveBeenCalledWith(
      'zhiyun-9-REVISION_PLANNING-RevisionTask-41.json',
      expect.objectContaining({ artifactType: 'RevisionTask' })
    )
    wrapper.unmount()
  })

  it('shows the report error when both the API and local download fail', async () => {
    downloadMarkdown.mockImplementation(() => {
      throw new Error('当前浏览器无法保存文件')
    })
    const { wrapper } = await mountTask()
    await wrapper.get('.tr-export button:nth-child(2)').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前浏览器无法保存文件')
    wrapper.unmount()
  })

  it('shows PDF page and figure inspect when the source version is PDF', async () => {
    api.mockImplementation((path: string) => {
      if (path === '/manuscripts/3/versions/1/inspect') {
        return Promise.resolve({
          manuscriptId: 3,
          versionNo: 1,
          format: 'PDF',
          pagePreview: true,
          hasFile: true,
          pageSpec: 'LETTER',
          pageCount: 1,
          needsVision: true,
          pages: [{ page: 1, widthPt: 612, heightPt: 792, spec: 'LETTER' }],
          figures: [{
            page: 1,
            number: 1,
            name: 'Im1',
            width: 80,
            height: 80,
            approxDpi: 9.4,
            caption: 'Blurry architecture diagram.',
            needsVision: true,
            anchor: 'p1-Im1'
          }],
          captions: []
        })
      }
      return Promise.resolve(jsonFor(path))
    })
    const { wrapper } = await mountTask()
    expect(wrapper.text()).toContain('PDF 页与图表')
    expect(wrapper.text()).toContain('需 Vision')
    expect(wrapper.text()).toContain('80×80 px · 9.4 DPI')
    wrapper.unmount()
  })

  it('cancels a RUNNING task from the result page', async () => {
    const running = { ...failedTask, id: 8, status: 'RUNNING', errorMessage: null }
    api.mockImplementation((path: string) => {
      if (path === '/workflows') {
        return Promise.resolve({ workflows: [{ id: 'FULL_REVIEW', name: '投稿前完整审校', capPoints: 10 }], billing: { tokensPerPoint: 2000 } })
      }
      if (path === '/me') return Promise.resolve({ quota: 3 })
      if (path === '/reviews/8') return Promise.resolve(running)
      if (path === '/reviews/8/artifacts') return Promise.resolve([])
      if (path === '/reviews/8/diff') return Promise.resolve({ official: 'body', candidate: 'body' })
      if (path === '/reviews/8/cancel') {
        return Promise.resolve({ ...running, status: 'FAILED', errorMessage: '已取消' })
      }
      if (path === '/manuscripts/3/versions/1/inspect') {
        return Promise.resolve({ manuscriptId: 3, versionNo: 1, format: 'MD', pagePreview: false })
      }
      return Promise.resolve({})
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/reviews/:id', component: Task },
        { path: '/history', component: Blank },
        { path: '/manuscripts/:id', component: Blank },
        { path: '/billing', component: Blank }
      ]
    })
    await router.push('/reviews/8')
    await router.isReady()
    const wrapper = mount(Task, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.get('.tr-retry button').text()).toBe('取消这次审校')
    await wrapper.get('.tr-retry button').trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/reviews/8/cancel', { method: 'POST' })
    wrapper.unmount()
  })
})
