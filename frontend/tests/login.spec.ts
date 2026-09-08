import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.fn()
vi.mock('../src/api.js', () => ({
  api: (...args: unknown[]) => api(...args)
}))

import Login from '../src/views/Login.vue'

const Blank = defineComponent({ template: '<div />' })

async function mountLogin() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: Login },
      { path: '/', component: Blank }
    ]
  })
  await router.push('/login')
  await router.isReady()
  return mount(Login, {
    global: { plugins: [router] }
  })
}

describe('Login', () => {
  beforeEach(() => {
    api.mockReset()
    sessionStorage.clear()
  })

  it('switches to register copy', async () => {
    const wrapper = await mountLogin()
    await wrapper.findAll('.auth-tab')[1].trigger('click')
    expect(wrapper.get('h1').text()).toBe('注册')
    expect(wrapper.text()).toContain('注册后送 3 额度')
  })

  it('stores the token and enters the app', async () => {
    window.matchMedia = ((query: string) => ({
      matches: true,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false
    })) as typeof window.matchMedia
    api.mockResolvedValue({ token: 'jwt-1' })
    const wrapper = await mountLogin()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/auth/login', expect.objectContaining({
      method: 'POST',
      body: expect.objectContaining({
        email: 'demo@zhiyun.dev',
        password: 'demo123456'
      })
    }))
    expect(sessionStorage.getItem('token')).toBe('jwt-1')
  })

  it('shows the server error', async () => {
    api.mockRejectedValue(new Error('邮箱或密码不对'))
    const wrapper = await mountLogin()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.err').text()).toBe('邮箱或密码不对')
    expect(sessionStorage.getItem('token')).toBeNull()
  })
})
