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

import ThemeToggle from '../src/components/ThemeToggle.vue'
import App from '../src/App.vue'
import OpsConsole from '../src/views/OpsConsole.vue'
import { routes } from '../src/router.js'
import { authGuard } from '../src/auth-guard.js'
import {
  THEME_STORAGE_KEY,
  applyTheme,
  bootTheme,
  resolveTheme,
  setTheme,
  toggleTheme
} from '../src/theme'

const Blank = defineComponent({ template: '<div class="blank-page" />' })
const DASHBOARD = {
  caption: '运行观测，非 SLA。',
  window: { range: '7d', taskLimit: 500, recentLimit: 20 },
  kpis: {
    tasks: 1,
    successRatePct: 100,
    inProgress: 0,
    leasesHeld: 0,
    failed: 0,
    checkpointSkipped: 0,
    tokens: 10
  },
  tasks: { started: 1, succeeded: 1, waitingAccept: 0, failed: 0, running: 0, pending: 0 },
  tokens: 10,
  trend: [],
  errorCodes: [],
  harness: { leasesHeld: 0, fencingRaised: 0, checkpointSkipped: 0 },
  leases: [],
  agents: [],
  recent: []
}

function mockMedia(dark: boolean) {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('prefers-color-scheme: dark') ? dark : false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false
  })) as typeof window.matchMedia
}

describe('theme', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.style.colorScheme = ''
    mockMedia(false)
  })

  it('defaults to the system scheme before the user toggles', () => {
    mockMedia(true)
    expect(resolveTheme()).toBe('dark')
    bootTheme()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    mockMedia(false)
    localStorage.clear()
    expect(resolveTheme()).toBe('light')
  })

  it('toggle writes localStorage and changes data-theme', () => {
    applyTheme('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(toggleTheme()).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(toggleTheme()).toBe('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
  })

  it('stored preference wins over the system scheme', () => {
    mockMedia(true)
    setTheme('light')
    expect(resolveTheme()).toBe('light')
    bootTheme()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('the toggle button click switches data-theme', async () => {
    applyTheme('light')
    const wrapper = mount(ThemeToggle)
    await wrapper.get('[data-testid="theme-toggle"]').trigger('click')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(wrapper.text()).toContain('深色')
    await wrapper.get('[data-testid="theme-toggle"]').trigger('click')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    wrapper.unmount()
  })
})

describe('theme in shells', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.setItem('token', 'jwt')
    document.documentElement.removeAttribute('data-theme')
    mockMedia(false)
    api.mockReset()
    api.mockImplementation(async (path: unknown) => {
      const p = String(path)
      if (p === '/me') return { displayName: '林', email: 'lin@zhiyun.dev', quota: 8, operator: true, ops: true }
      if (p.startsWith('/ops/observability') || p.startsWith('/observability')) return DASHBOARD
      return {}
    })
  })

  it('sits beside quota on the product bar and does not drop the route', async () => {
    const mapped = (routes as RouteRecordRaw[]).map((record) => (
      record.redirect ? record : { ...record, component: Blank }
    )) as RouteRecordRaw[]
    const router = createRouter({ history: createMemoryHistory(), routes: mapped })
    router.beforeEach(authGuard)
    await router.push('/history')
    await router.isReady()
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        stubs: { AmbientCanvas: true, Chat: true, ToastHost: true }
      }
    })
    await flushPromises()
    const toggle = wrapper.get('.stage-quota [data-testid="theme-toggle"]')
    expect(wrapper.find('[data-testid="recharge-open"]').exists()).toBe(true)
    await toggle.trigger('click')
    await flushPromises()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(router.currentRoute.value.path).toBe('/history')
    wrapper.unmount()
  })

  it('sits on the ops top bar and switches the same data-theme', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/ops', component: OpsConsole },
        { path: '/ops/login', component: Blank },
        { path: '/', component: Blank }
      ]
    })
    await router.push('/ops')
    await router.isReady()
    applyTheme('light')
    const wrapper = mount(OpsConsole, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.get('.ops-top-tools [data-testid="theme-toggle"]').trigger('click')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(router.currentRoute.value.path).toBe('/ops')
    wrapper.unmount()
  })
})
