import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.fn()
vi.mock('../src/api.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api.js')>()
  return {
    ...actual,
    api: (...args: unknown[]) => api(...args)
  }
})

import History from '../src/views/History.vue'

const Blank = defineComponent({ template: '<div />' })

async function mountHistory(refreshMe = vi.fn().mockResolvedValue(undefined)) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/history', component: History },
      { path: '/reviews/:id', component: Blank },
      { path: '/manuscripts/:id', component: Blank },
      { path: '/', component: Blank }
    ]
  })
  await router.push('/history')
  await router.isReady()
  const wrapper = mount(History, {
    global: {
      plugins: [router],
      provide: { refreshMe }
    }
  })
  await flushPromises()
  return { wrapper, router, refreshMe }
}

describe('History retry', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockImplementation((path: string) => {
      if (String(path).startsWith('/reviews?') || path === '/reviews') {
        return Promise.resolve({
          items: [{
            id: 12,
            manuscriptId: 4,
            manuscriptTitle: 'ACL draft',
            workflowName: '引用核验',
            status: 'FAILED',
            unread: true,
            errorMessage: 'timeout',
            createdAt: '2026-09-07T03:00:00+08:00'
          }],
          total: 1,
          page: 1,
          size: 10
        })
      }
      if (path === '/reviews/12/retry') {
        return Promise.resolve({ id: 12, status: 'PENDING' })
      }
      if (path === '/inbox/read') {
        return Promise.resolve({ items: [], unreadCount: 0 })
      }
      return Promise.resolve({})
    })
  })

  it('marks all inbox read when entering the list so the badge can drop', async () => {
    const { refreshMe } = await mountHistory()
    expect(api).toHaveBeenCalledWith('/inbox/read', { method: 'POST', body: { all: true } })
    expect(refreshMe).toHaveBeenCalled()
  })

  it('offers checkpoint retry on FAILED rows and navigates to the task', async () => {
    const { wrapper, router } = await mountHistory()
    expect(wrapper.text()).toContain('未读')
    expect(wrapper.text()).toContain('任务 ID')
    expect(wrapper.text()).toContain('12')
    const btn = wrapper.findAll('button').find((b) => b.text().includes('从检查点继续'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/reviews/12/retry', { method: 'POST' })
    expect(router.currentRoute.value.path).toBe('/reviews/12')
    wrapper.unmount()
  })
})

describe('History cancel', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockImplementation((path: string, options?: { method?: string }) => {
      if (String(path).startsWith('/reviews?') || path === '/reviews') {
        return Promise.resolve({
          items: [{
            id: 15,
            manuscriptId: 4,
            manuscriptTitle: 'ACL draft',
            workflowName: '引用核验',
            status: 'RUNNING',
            createdAt: '2026-09-07T03:00:00+08:00'
          }],
          total: 1,
          page: 1,
          size: 10
        })
      }
      if (path === '/reviews/15/cancel' && options?.method === 'POST') {
        return Promise.resolve({ id: 15, status: 'FAILED', errorMessage: '已取消' })
      }
      if (path === '/inbox/read') {
        return Promise.resolve({ items: [], unreadCount: 0 })
      }
      return Promise.resolve({})
    })
  })

  it('offers cancel on RUNNING rows', async () => {
    const { wrapper } = await mountHistory()
    expect(wrapper.text()).toContain('取消这次审校')
    expect(wrapper.text()).not.toContain('从检查点继续')
    const btn = wrapper.findAll('button').find((b) => b.text().includes('取消这次审校'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/reviews/15/cancel', { method: 'POST' })
    wrapper.unmount()
  })
})
