import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, nextTick, ref } from 'vue'
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
import Home from '../src/views/Home.vue'
import Billing from '../src/views/Billing.vue'
import Account from '../src/views/Account.vue'
import RechargeModal from '../src/components/RechargeModal.vue'
import { routes } from '../src/router.js'
import { authGuard } from '../src/auth-guard.js'

const Blank = defineComponent({ template: '<div class="blank-page" />' })
const me = {
  displayName: '林',
  email: 'lin@zhiyun.dev',
  quota: 8,
  userId: 1,
  tenantName: '实验室',
  tenantId: 1,
  createdAt: '2026-01-01T00:00:00+08:00',
  unreadInbox: 2,
  operator: false
}

let lastCustomYuan = 10

function stubApi() {
  me.quota = 8
  lastCustomYuan = 10
  api.mockImplementation(async (path: unknown, options?: { method?: string, body?: { amountYuan?: number } }) => {
    const p = String(path)
    const method = options?.method || 'GET'
    if (p === '/me') return { ...me }
    if (p === '/projects') return [{ id: 1, name: 'ACL' }]
    if (p.startsWith('/manuscripts')) {
      return { items: [{ id: 11, title: 'demo.pdf', currentVersion: 1 }], total: 1, page: 1, size: 5 }
    }
    if (p === '/plans') {
      return [{ id: 2, name: '专业版', code: 'pro', priceCents: 9900, quotaAmount: 120, description: '120点' }]
    }
    if (p.includes('/mock-pay')) {
      me.quota = Number(me.quota || 0) + lastCustomYuan
      return { id: 'ZY9', status: 'PAID', quotaAmount: lastCustomYuan }
    }
    if (method === 'POST' && p === '/orders') {
      const body = options?.body || {}
      lastCustomYuan = 'amountYuan' in body ? Number(body.amountYuan) : 120
      return { id: 'ZY9', status: 'PENDING', quotaAmount: lastCustomYuan }
    }
    if (p.startsWith('/orders/')) {
      return {
        id: 'ZY1',
        kind: 'CUSTOM',
        planName: '灵活充值',
        amountCents: 1000,
        quotaAmount: 10,
        status: 'PENDING',
        createdAt: '2026-09-07T10:00:00+08:00',
        ledger: []
      }
    }
    if (p.startsWith('/orders')) {
      return {
        items: [{
          id: 'ZY1',
          kind: 'CUSTOM',
          planName: '灵活充值',
          amountCents: 1000,
          quotaAmount: 10,
          status: 'PENDING',
          createdAt: '2026-09-07T10:00:00+08:00'
        }],
        total: 1,
        page: 1,
        size: 5
      }
    }
    if (p === '/models' || p === '/me/models' || p.startsWith('/models/')) {
      return {
        agents: [
          { id: 'CITATION_INTEGRITY', name: '引用核验', summary: '核对参考文献与 DOI。', mode: 'PLATFORM' },
          { id: 'FIGURE_PDF', name: '图表检查', summary: '看图与版式，必要时走视觉模型。', mode: 'PLATFORM' }
        ],
        platform: { live: false, paperModel: 'qwen-plus', visionModel: 'qwen-vl' },
        locked: {
          cs: { name: '云笺', note: '由平台提供，不可更换', model: 'cs-model' },
          rag: { name: '检索', note: '由平台提供，不可更换', embeddingModel: 'emb', rerankModel: 'rerank' }
        },
        skillFee: { note: '自备模型时 token 走你自己的云账单，平台只收技能与提示词服务费。' },
        providers: []
      }
    }
    if (p.startsWith('/ledger')) return { items: [], total: 0, page: 1, size: 5 }
    if (p === '/ops/knowledge' || p.startsWith('/ops/knowledge')) {
      return { bundled: [], uploaded: [], publicChunks: 0 }
    }
    if (p.startsWith('/inbox')) return { items: [], unreadCount: 2 }
    if (p === '/eval') throw new Error('settings must not call /eval')
    if (p.startsWith('/observability')) {
      return { caption: '运行观测，非 SLA。', kpis: {}, tasks: {}, recent: [], agents: [], errorCodes: [], trend: [], harness: {} }
    }
    return {}
  })
}

function shellRoutes(): RouteRecordRaw[] {
  return (routes as RouteRecordRaw[]).map((record) => (
    record.redirect ? record : { ...record, component: Blank }
  )) as RouteRecordRaw[]
}

async function makeRouter(start = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: shellRoutes()
  })
  router.beforeEach(authGuard)
  await router.push(start)
  await router.isReady()
  return router
}

describe('route table', () => {
  beforeEach(() => {
    sessionStorage.setItem('token', 'jwt')
  })

  it('keeps /chat as a home redirect', async () => {
    const router = await makeRouter('/login')
    await router.push('/chat')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('folds orders and models into billing and settings', async () => {
    const router = await makeRouter('/login')
    await router.push('/orders')
    expect(router.currentRoute.value.path).toBe('/billing')
    await router.push('/orders/ZY1')
    expect(router.currentRoute.value.path).toBe('/billing')
    expect(router.currentRoute.value.query.order).toBe('ZY1')
    await router.push('/models')
    expect(router.currentRoute.value.path).toBe('/account')
    expect(router.currentRoute.value.query.panel).toBe('models')
  })
})

describe('sidebar', () => {
  beforeEach(() => {
    sessionStorage.setItem('token', 'jwt')
    stubApi()
  })

  async function mountShell(start = '/') {
    const router = await makeRouter(start)
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        stubs: {
          AmbientCanvas: true,
          Chat: { template: '<button type="button" class="cs-fab">云笺</button>' },
          ToastHost: true
        }
      }
    })
    await flushPromises()
    return { wrapper, router }
  }

  it('shows four nav items and the 云笺 FAB', async () => {
    const { wrapper } = await mountShell('/')
    const labels = wrapper.findAll('.side-nav a').map((a) => a.text().replace(/\d+\+?/g, '').replace(/\s+/g, ' ').trim())
    expect(labels).toEqual(['论文', '审校', '订单', '设置'])
    expect(wrapper.find('.nav-badge').text()).toBe('2')
    expect(wrapper.find('.cs-fab').exists()).toBe(true)
    expect(wrapper.find('.sidebar .quota-chip').exists()).toBe(false)
    expect(wrapper.find('.stage-bar .quota-chip').exists()).toBe(true)
    expect(wrapper.find('[data-testid="recharge-open"]').exists()).toBe(true)
    expect(wrapper.find('.side-quota-hint').exists()).toBe(false)
    expect(wrapper.get('.side-account strong').text()).toBe('林')
    expect(wrapper.find('.sidebar input').exists()).toBe(false)
    expect(wrapper.find('[data-testid="stage-back"]').exists()).toBe(false)
  })

  it('opens account profile when the sidebar user card is clicked', async () => {
    const { wrapper, router } = await mountShell('/')
    await wrapper.get('[data-testid="side-account"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/account')
    expect(router.currentRoute.value.query.panel).toBe('profile')
    expect(wrapper.find('[data-testid="stage-back"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows back on nested pages and uses history when available', async () => {
    const { wrapper, router } = await mountShell('/')
    await router.push('/manuscripts/11')
    await flushPromises()
    expect(wrapper.find('[data-testid="stage-back"]').exists()).toBe(true)
    await wrapper.get('[data-testid="stage-back"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    wrapper.unmount()
  })

  it('opens the recharge modal from the top-right button', async () => {
    const { wrapper } = await mountShell('/')
    await wrapper.get('[data-testid="recharge-open"]').trigger('click')
    await flushPromises()
    const overlay = document.body.querySelector('.recharge-overlay')
    expect(overlay?.textContent).toContain('专业版')
    expect(overlay?.textContent).toContain('灵活充值')
    wrapper.unmount()
  })

  it('highlights 论文 for home and manuscript detail', async () => {
    const { wrapper, router } = await mountShell('/')
    expect(wrapper.find('.side-nav a.is-on').text()).toBe('论文')
    await router.push('/manuscripts/11')
    await flushPromises()
    expect(wrapper.find('.side-nav a.is-on').text()).toBe('论文')
  })

  it('highlights 审校 for history and review detail', async () => {
    const { wrapper, router } = await mountShell('/history')
    expect(wrapper.find('.side-nav a.is-on').text()).toContain('审校')
    await router.push('/reviews/5')
    await flushPromises()
    expect(wrapper.find('.side-nav a.is-on').text()).toContain('审校')
  })

  it('highlights 订单 for billing and compatible order links', async () => {
    const { wrapper, router } = await mountShell('/billing')
    expect(wrapper.find('.side-nav a.is-on').text()).toBe('订单')
    await router.push('/orders/ZY1')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/billing')
    expect(wrapper.find('.side-nav a.is-on').text()).toBe('订单')
  })

  it('highlights 设置 on the merged account page', async () => {
    const { wrapper } = await mountShell('/account')
    expect(wrapper.find('.side-nav a.is-on').text()).toBe('设置')
  })
})

describe('RechargeModal', () => {
  beforeEach(() => {
    sessionStorage.setItem('token', 'jwt')
    stubApi()
  })

  it('blocks custom recharge below 10 yuan and mock-pays a valid amount', async () => {
    const wrapper = mount(RechargeModal, {
      props: { open: true },
      attachTo: document.body,
      global: { provide: { refreshMe: vi.fn().mockResolvedValue(undefined) } }
    })
    await flushPromises()
    const overlay = () => document.body.querySelector('.recharge-overlay') as HTMLElement
    expect(overlay()?.textContent).toContain('灵活充值')

    const input = overlay().querySelector('.custom-input') as HTMLInputElement
    input.value = '9'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    const underMin = overlay().querySelector('.recharge-custom .btn-accent') as HTMLButtonElement
    expect(underMin.disabled).toBe(true)

    const quick20 = Array.from(overlay().querySelectorAll('.quick-amt button')).find((b) => b.textContent?.trim() === '¥20')
    quick20?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    const pay20 = overlay().querySelector('.recharge-custom .btn-accent') as HTMLButtonElement
    expect(pay20.textContent).toContain('充值 20 额度')
    pay20.click()
    await flushPromises()
    expect(overlay().textContent).toContain('确认充值')
    expect(overlay().textContent).toContain('20 额度')

    const confirm = Array.from(overlay().querySelectorAll('.modal-actions .btn-accent')).find((b) => b.textContent?.includes('确认并到账')) as HTMLButtonElement
    confirm.click()
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/orders', { method: 'POST', body: { amountYuan: 20 } })
    expect(api).toHaveBeenCalledWith('/orders/ZY9/mock-pay', { method: 'POST', body: {} })
    expect(me.quota).toBe(28)
    expect(wrapper.emitted('paid')).toBeTruthy()
    wrapper.unmount()
  })
})

describe('Home', () => {
  beforeEach(() => {
    sessionStorage.setItem('token', 'jwt')
    stubApi()
  })

  it('keeps greeting, upload and papers, without the workflow gallery or quota chip', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: Home },
        { path: '/manuscripts/:id', component: Blank }
      ]
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(Home, {
      global: {
        plugins: [router],
        provide: { refreshMe: () => Promise.resolve() }
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('你好，林')
    expect(wrapper.text()).toContain('当前课题')
    expect(wrapper.text()).toContain('上传到')
    expect(wrapper.text()).toContain('里的论文')
    expect(wrapper.text()).not.toContain('三种检查范围')
    expect(wrapper.find('.quota-chip').exists()).toBe(false)
    expect(api.mock.calls.some((call) => String(call[0]).startsWith('/workflows'))).toBe(false)
  })
})

describe('Billing', () => {
  beforeEach(() => {
    sessionStorage.setItem('token', 'jwt')
    stubApi()
  })

  async function mountBilling(start = '/billing') {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/billing', component: Billing },
        { path: '/account', component: Blank },
        { path: '/orders/:id', redirect: (to) => ({ path: '/billing', query: { order: String(to.params.id) } }) },
        { path: '/orders', redirect: '/billing' }
      ]
    })
    await router.push(start)
    await router.isReady()
    const wrapper = mount(Billing, {
      global: {
        plugins: [router],
        provide: { refreshMe: () => Promise.resolve() }
      }
    })
    await flushPromises()
    return { wrapper, router }
  }

  it('shows ledger only, without recharge packages', async () => {
    const { wrapper } = await mountBilling()
    expect(wrapper.get('.page-kicker').text()).toBe('订单')
    expect(wrapper.text()).toContain('额度流水')
    expect(wrapper.text()).toContain('充值和消耗记录')
    expect(wrapper.text()).not.toContain('按金额充值')
    expect(wrapper.text()).not.toContain('专业版')
    expect(wrapper.text()).not.toContain('我的订单')
  })

  it('opens order detail in a drawer from a query link', async () => {
    const { wrapper, router } = await mountBilling('/billing?order=ZY1')
    expect(router.currentRoute.value.path).toBe('/billing')
    expect(router.currentRoute.value.query.order).toBe('ZY1')
    await flushPromises()
    await nextTick()
    const drawer = document.body.querySelector('.drawer')
    expect(drawer).toBeTruthy()
    expect(drawer?.textContent).toContain('订单详情')
    const close = Array.from(drawer!.querySelectorAll('button')).find((b) => b.textContent?.trim() === '关闭')
    expect(close).toBeTruthy()
    close?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    await nextTick()
    expect(router.currentRoute.value.query.order).toBeUndefined()
    expect(document.body.querySelector('.drawer')).toBeFalsy()
    wrapper.unmount()
  })

  it('opens the drawer from a compatible /orders/:id link', async () => {
    const { wrapper, router } = await mountBilling('/orders/ZY1')
    expect(router.currentRoute.value.path).toBe('/billing')
    expect(router.currentRoute.value.query.order).toBe('ZY1')
    await flushPromises()
    expect(document.body.querySelector('.drawer')?.textContent).toContain('订单详情')
    wrapper.unmount()
  })
})

describe('Settings', () => {
  beforeEach(() => {
    sessionStorage.setItem('token', 'jwt')
    stubApi()
  })

  async function mountAccount(start = '/account', operator = false) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/account', component: Account },
        { path: '/billing', component: Blank },
        { path: '/history', component: Blank },
        { path: '/ops', component: Blank }
      ]
    })
    await router.push(start)
    await router.isReady()
    if (operator) me.operator = true
    const wrapper = mount(Account, {
      global: {
        plugins: [router],
        provide: {
          refreshMe: () => Promise.resolve(),
          avatarSrc: ref('')
        }
      }
    })
    await flushPromises()
    return { wrapper, router }
  }

  it('shows settings entries without dumping models, ledger or knowledge', async () => {
    const { wrapper } = await mountAccount()
    expect(wrapper.get('.page-kicker').text()).toBe('设置')
    expect(wrapper.text()).toContain('账户资料')
    expect(wrapper.text()).toContain('模型配置')
    expect(wrapper.text()).toContain('额度流水')
    expect(wrapper.text()).not.toContain('可观测')
    expect(wrapper.text()).not.toContain('打开观测台')
    expect(wrapper.text()).not.toContain('公共知识运营')
    expect(wrapper.find('[data-testid="display-name-input"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('保存资料')
    expect(wrapper.text()).not.toContain('充值和消耗记录')
    expect(wrapper.text()).not.toContain('引用核验')
    expect(wrapper.text()).not.toContain('跑检索测评')
    expect(wrapper.text()).not.toContain('检索测评')
    expect(api.mock.calls.some((call) => String(call[0]) === '/eval')).toBe(false)
    wrapper.unmount()
  })

  it('shows the knowledge entry only for operators', async () => {
    const { wrapper } = await mountAccount('/account', true)
    expect(wrapper.text()).toContain('公共知识运营')
    expect(wrapper.text()).not.toContain('打开观测台')
    expect(wrapper.find('[data-testid="obs-settings"]').exists()).toBe(false)
    wrapper.unmount()
    me.operator = false
  })

  it('keeps the display name read-only until the settings entry is confirmed', async () => {
    const { wrapper } = await mountAccount('/account?panel=profile')
    expect(wrapper.get('[data-testid="display-name"]').text()).toBe('林')
    expect(wrapper.find('[data-testid="display-name-input"]').exists()).toBe(false)
    expect(api.mock.calls.some((call) => call[1] && (call[1] as { method?: string }).method === 'PUT')).toBe(false)

    await wrapper.get('[data-testid="edit-display-name"]').trigger('click')
    await nextTick()
    expect(wrapper.text()).toContain('当前显示名：林')
    const input = wrapper.get('[data-testid="display-name-input"]')
    await input.setValue('先取消')
    await wrapper.get('.name-edit .btn-ghost').trigger('click')
    await nextTick()
    expect(wrapper.find('[data-testid="display-name-input"]').exists()).toBe(false)
    expect(api.mock.calls.some((call) => call[1] && (call[1] as { method?: string }).method === 'PUT')).toBe(false)

    await wrapper.get('[data-testid="edit-display-name"]').trigger('click')
    await nextTick()
    await wrapper.get('[data-testid="display-name-input"]').setValue('林改')
    await nextTick()
    await wrapper.get('form.name-edit').trigger('submit')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/me', {
      method: 'PUT',
      body: { displayName: '林改' }
    })
    wrapper.unmount()
  })

  it('returns from a settings panel to the hub', async () => {
    const { wrapper, router } = await mountAccount('/account?panel=profile')
    expect(wrapper.get('.page-title').text()).toBe('账户资料')
    await wrapper.get('.settings-back').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/account')
    expect(router.currentRoute.value.query.panel).toBeFalsy()
    expect(wrapper.get('.page-title').text()).toBe('设置')
    wrapper.unmount()
  })

  it('opens models as a settings subview and sends quota to the orders page', async () => {
    const { wrapper, router } = await mountAccount()
    await wrapper.findAll('.settings-card')[1].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.panel).toBe('models')
    expect(wrapper.text()).toContain('引用核验')
    expect(wrapper.text()).toContain('由平台提供，不可更换')
    expect(wrapper.find('[data-testid="display-name-input"]').exists()).toBe(false)

    await wrapper.get('.settings-back').trigger('click')
    await flushPromises()
    await wrapper.findAll('.settings-card')[2].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/billing')
    wrapper.unmount()
  })

  it('does not open the observability console from C-end settings', async () => {
    const { wrapper, router } = await mountAccount('/account', true)
    expect(wrapper.find('[data-testid="obs-settings"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('打开观测台')
    expect(router.currentRoute.value.path).toBe('/account')
    wrapper.unmount()
  })
})
