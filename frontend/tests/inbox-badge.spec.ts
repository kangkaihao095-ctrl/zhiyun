import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RouteRecordRaw } from 'vue-router'

const api = vi.fn()
vi.mock('../src/api.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api.js')>()
  return {
    ...actual,
    api: (...args: unknown[]) => api(...args),
    token: () => sessionStorage.getItem('token'),
    fetchAvatarBlobUrl: async () => ''
  }
})

import App from '../src/App.vue'
import History from '../src/views/History.vue'
import { routes } from '../src/router.js'
import { authGuard } from '../src/auth-guard.js'

const Blank = defineComponent({ template: '<div class="blank-page" />' })

describe('sidebar 审校 badge', () => {
  beforeEach(() => {
    sessionStorage.setItem('token', 'jwt')
    let unread = 2
    api.mockReset()
    api.mockImplementation(async (path: unknown, options?: { method?: string }) => {
      const p = String(path)
      if (p === '/me') {
        return {
          displayName: '林',
          email: 'lin@zhiyun.dev',
          quota: 8,
          unreadInbox: unread
        }
      }
      if (p === '/inbox/read' && options?.method === 'POST') {
        unread = 0
        return { items: [], unreadCount: 0 }
      }
      if (p.startsWith('/reviews')) {
        return {
          items: [{
            id: 12,
            manuscriptId: 4,
            manuscriptTitle: 'ACL draft',
            workflowName: '引用核验',
            status: 'DONE',
            unread: unread > 0,
            createdAt: '2026-09-07T03:00:00+08:00'
          }],
          total: 1,
          page: 1,
          size: 5
        }
      }
      return {}
    })
  })

  it('clears the unread count after entering the review list', async () => {
    const mapped = (routes as RouteRecordRaw[]).map((record) => {
      if (record.redirect) return record
      if (record.path === '/history') return { ...record, component: History }
      return { ...record, component: Blank }
    }) as RouteRecordRaw[]
    const router = createRouter({
      history: createMemoryHistory(),
      routes: mapped
    })
    router.beforeEach(authGuard)
    await router.push('/history')
    await router.isReady()
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        stubs: {
          AmbientCanvas: true,
          Chat: true,
          ToastHost: true
        }
      }
    })
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/inbox/read', { method: 'POST', body: { all: true } })
    expect(wrapper.find('.nav-badge').exists()).toBe(false)
    wrapper.unmount()
  })
})
