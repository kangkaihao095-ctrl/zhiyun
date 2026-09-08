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

import Home from '../src/views/Home.vue'

const Blank = defineComponent({ template: '<div />' })

async function mountHome() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Home },
      { path: '/manuscripts/:id', component: Blank }
    ]
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(Home, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('Home project filter', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockImplementation((path: string, options?: { method?: string }) => {
      if (path === '/me') return Promise.resolve({ displayName: 'A', quota: 3 })
      if (path === '/projects' && options?.method === 'POST') {
        return Promise.resolve({ id: 3, name: '新课题' })
      }
      if (path === '/projects') {
        return Promise.resolve([
          { id: 1, name: '我的论文' },
          { id: 2, name: 'ACL 投稿' }
        ])
      }
      if (String(path).startsWith('/manuscripts')) {
        const url = new URL('http://x' + path)
        const projectId = url.searchParams.get('projectId')
        const size = url.searchParams.get('size')
        if (projectId === '2') {
          return Promise.resolve({
            items: [{ id: 20, title: 'beta.md', currentVersion: 1 }],
            total: 1,
            page: 1,
            size: Number(size || 5)
          })
        }
        if (projectId === '1') {
          return Promise.resolve({
            items: [{ id: 10, title: 'alpha.md', currentVersion: 1 }],
            total: 1,
            page: 1,
            size: Number(size || 5)
          })
        }
        return Promise.resolve({ items: [], total: 0, page: 1, size: 5 })
      }
      return Promise.resolve({})
    })
  })

  it('selects the first project and only lists its papers', async () => {
    const wrapper = await mountHome()
    expect(wrapper.text()).toContain('当前课题：我的论文')
    expect(wrapper.text()).toContain('上传到「我的论文」')
    expect(wrapper.text()).toContain('alpha.md')
    expect(wrapper.text()).not.toContain('beta.md')
    expect(api).toHaveBeenCalledWith(expect.stringMatching(/\/manuscripts\?.*projectId=1/))
    expect(api).toHaveBeenCalledWith(expect.stringContaining('size=5'))

    const chips = wrapper.findAll('[data-testid="project-chip"]')
    expect(chips[0].classes()).toContain('on')
    await chips[1].trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith(expect.stringContaining('projectId=2'))
    expect(wrapper.text()).toContain('当前课题：ACL 投稿')
    expect(wrapper.text()).toContain('上传到「ACL 投稿」')
    expect(wrapper.text()).toContain('beta.md')
    expect(wrapper.text()).not.toContain('alpha.md')
    wrapper.unmount()
  })
})
