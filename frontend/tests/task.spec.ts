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
  if (path.startsWith('/reviews/9/usage')) {
    return {
      durationMs: 12000,
      tokens: 820,
      quota: 1,
      settled: true,
      inProgress: false,
      nodes: [{
        name: '引用核验',
        durationMs: 12000,
        tokens: 820,
        quota: 1,
        skipped: false,
        status: 'DONE'
      }]
    }
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
  return { wrapper, refreshMe, router }
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
    expect(wrapper.get('[data-testid="task-tab-confirm"]').classes()).toContain('on')
    expect(wrapper.get('[data-testid="task-tab-confirm"]').text()).toContain('2')
    expect(wrapper.get('[data-testid="task-tab-issues"]').text()).toContain('1')
    expect(wrapper.get('[data-testid="task-tab-revise"]').text()).toContain('1')
    const confirm = wrapper.get('[data-testid="task-panel-confirm"]').text()
    expect(confirm).toContain('需要人工处理')
    expect(confirm).toContain('核对幽灵引用')
    expect(confirm).toContain('为什么高')
    expect(confirm).toContain('会改引用结论与作者责任')
    expect(confirm).toContain('本条无需改稿对比')
    expect(wrapper.find('[data-testid="trace-waterfall"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('时间轴瀑布图')
    expect(wrapper.text()).not.toContain('Agent Trace')
    expect(wrapper.get('[data-testid="task-usage-summary"]').text()).toContain('已扣 1 额度')
    expect(wrapper.get('[data-testid="task-usage-summary"]').text()).toContain('820 token')
    expect(wrapper.text()).not.toContain('fencing')
    expect(wrapper.text()).not.toContain('lease')
    expect(wrapper.text()).not.toContain('skillVersion')
    expect(wrapper.text()).not.toContain('errorCode')

    await wrapper.get('[data-testid="task-tab-overview"]').trigger('click')
    await flushPromises()
    const overview = wrapper.get('[data-testid="task-panel-overview"]').text()
    expect(overview).toContain('本次用量')
    expect(overview).toContain('耗时')
    expect(overview).toContain('token')
    expect(overview).toContain('额度')
    expect(overview).toContain('引用核验')
    expect(overview).toContain('约 12 秒')
    expect(overview).toContain('已结算')
    expect(wrapper.get('[data-testid="task-usage-cols"]').text()).toContain('820 token')
    expect(wrapper.get('[data-testid="task-usage-settled"]').text()).toContain('约 1 额度')
    expect(overview).toContain('检查点管道')
    expect(overview).toContain('任务 ID')
    expect(overview).not.toContain('fencing')
    expect(overview).not.toContain('checkpoint')
    expect(overview).toContain('失败')
    expect(overview).toContain('图表检查')
    expect(overview).toContain('模型输出格式校验失败，请重试。')
    expect(overview).not.toContain('structured output failed after retry')
    expect(overview).toContain('技术详情')
    expect(wrapper.find('.tr-tech').attributes('open')).toBeUndefined()
    expect(overview).toContain('从检查点继续')
    expect(overview).toContain('导出 Artifact')
    expect(overview).toContain('导出汇总')
    expect(wrapper.text()).not.toContain('PDF 页与图表')
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
      if (path.startsWith('/reviews/8/usage')) {
        return Promise.resolve({
          durationMs: 5000,
          tokens: 400,
          quota: 1,
          settled: false,
          inProgress: true,
          nodes: [
            { name: '引用核验', durationMs: 5000, tokens: 400, quota: 1, skipped: false, status: 'DONE' },
            { name: '图表检查', durationMs: null, tokens: null, quota: null, skipped: false, status: 'RUNNING' }
          ]
        })
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
    expect(wrapper.get('[data-testid="task-usage-summary"]').text()).toContain('预计 1 额度')
    expect(wrapper.get('[data-testid="task-usage-summary"]').text()).toContain('进行中')
    await wrapper.get('[data-testid="task-tab-overview"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="task-usage-settled"]').text()).toContain('引用核验')
    expect(wrapper.get('[data-testid="task-usage-running"]').text()).toContain('图表检查')
    expect(wrapper.get('[data-testid="task-usage-running"]').text()).toContain('进行中')
    await wrapper.get('.tr-retry button').trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/reviews/8/cancel', { method: 'POST' })
    wrapper.unmount()
  })

  it('puts Accept / Reject on the default 需你确认 tab for WAITING_ACCEPT', async () => {
    api.mockImplementation((path: string) => {
      if (path === '/reviews/9') return Promise.resolve({ ...failedTask, status: 'WAITING_ACCEPT', errorMessage: null })
      return Promise.resolve(jsonFor(path))
    })
    const { wrapper } = await mountTask()
    expect(wrapper.get('[data-testid="task-tab-confirm"]').classes()).toContain('on')
    const confirm = wrapper.get('[data-testid="task-panel-confirm"]').text()
    expect(confirm).toContain('全部接受')
    expect(confirm).toContain('不采纳')
    expect(wrapper.find('[data-testid="trace-waterfall"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows git-style patch diffs on 需你确认 and 改稿', async () => {
    api.mockImplementation((path: string) => {
      if (path === '/reviews/9') {
        return Promise.resolve({ ...failedTask, status: 'WAITING_ACCEPT', errorMessage: null })
      }
      if (path === '/reviews/9/artifacts') {
        const base = jsonFor(path) as Array<Record<string, unknown>>
        return Promise.resolve([
          ...base,
          {
            id: 44,
            agent: 'REVISION_EXECUTION',
            artifactType: 'RevisionPatch',
            payload: JSON.stringify({
              body: [
                {
                  patchId: 'rp-h',
                  issueId: 'iss-h',
                  originalText: 'see 10.0000/ghost.doi',
                  proposedText: 'drop the unverified citation',
                  reason: 'DOI not found'
                },
                {
                  patchId: 'rp-a',
                  issueId: 'iss-a',
                  originalText: 'this result shows',
                  proposedText: 'these results suggest',
                  reason: 'hedge the claim'
                }
              ]
            })
          }
        ])
      }
      if (path === '/reviews/9/merge-preview') {
        return Promise.resolve({
          official: 'keep\nold sentence\nend',
          preview: 'keep\nnew sentence\nend',
          score: 80,
          grade: 'B',
          points: [],
          reasons: [],
          mode: 'candidate'
        })
      }
      return Promise.resolve(jsonFor(path))
    })
    const { wrapper } = await mountTask()
    const confirm = wrapper.get('[data-testid="task-panel-confirm"]')
    expect(confirm.get('[data-testid="patch-diff"]').text()).toContain('see 10.0000/ghost.doi')
    expect(confirm.get('.diff-line.del').text()).toContain('see 10.0000/ghost.doi')
    expect(confirm.get('.diff-line.add').text()).toContain('drop the unverified citation')
    expect(confirm.text()).not.toContain('this result shows')

    await wrapper.get('[data-testid="task-tab-revise"]').trigger('click')
    await flushPromises()
    const revise = wrapper.get('[data-testid="task-panel-revise"]')
    expect(revise.get('[data-testid="patch-diff"]').text()).toContain('this result shows')
    const delText = revise.findAll('.diff-line.del').map((row) => row.text()).join('\n')
    const addText = revise.findAll('.diff-line.add').map((row) => row.text()).join('\n')
    expect(delText).toContain('this result shows')
    expect(addText).toContain('these results suggest')
    expect(delText).toContain('old sentence')
    expect(addText).toContain('new sentence')
    wrapper.unmount()
  })

  it('jumps from a finding to 稿件对照 with an anchor', async () => {
    api.mockImplementation((path: string) => {
      if (path === '/reviews/9/diff') {
        return Promise.resolve({
          official: 'See 10.0000/ghost.doi in the list.',
          candidate: 'See 10.0000/ghost.doi in the list.'
        })
      }
      return Promise.resolve(jsonFor(path))
    })
    const { wrapper, router } = await mountTask()
    const cards = wrapper.findAll('[data-testid="task-panel-confirm"] .finding-card')
    await cards[0].trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="task-tab-manuscript"]').classes()).toContain('on')
    const hit = wrapper.get('#zy-hit')
    expect(hit.attributes('data-anchor')).toBe('10.0000/ghost.doi')
    expect(hit.text()).toContain('10.0000/ghost.doi')
    expect(router.currentRoute.value.query.hit).toBe('iss-h')
    expect(router.currentRoute.value.query.anchor).toBe('10.0000/ghost.doi')
    expect(router.currentRoute.value.hash).toBe('#zy-hit')
    wrapper.unmount()
  })

  it('writes empty copy when this review has nothing to confirm', async () => {
    api.mockImplementation((path: string) => {
      if (path === '/reviews/9/artifacts') return Promise.resolve([])
      if (path === '/reviews/9/diff') return Promise.resolve({ official: '', candidate: '' })
      return Promise.resolve(jsonFor(path))
    })
    const { wrapper } = await mountTask()
    expect(wrapper.get('[data-testid="task-empty-confirm"]').text()).toBe('这次没有需你确认。')
    expect(wrapper.get('[data-testid="task-tab-confirm"]').text()).toContain('0')
    await wrapper.get('[data-testid="task-tab-issues"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="task-empty-issues"]').text()).toBe('这次没有问题与证据。')
    await wrapper.get('[data-testid="task-tab-revise"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="task-empty-revise"]').text()).toBe('这次没有改稿。')
    await wrapper.get('[data-testid="task-tab-manuscript"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="task-empty-manuscript"]').text()).toBe('这次没有稿件对照。')
    wrapper.unmount()
  })
})
