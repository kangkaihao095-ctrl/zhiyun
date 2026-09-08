import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import { authGuard } from '../src/auth-guard.js'

const Blank = defineComponent({ template: '<div />' })

function makeRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: Blank },
      { path: '/', component: Blank },
      { path: '/chat', redirect: '/' },
      { path: '/account', component: Blank }
    ]
  })
  router.beforeEach(authGuard)
  return router
}

describe('authGuard', () => {
  it('sends anonymous users to login', () => {
    expect(authGuard({ path: '/' })).toBe('/login')
    expect(authGuard({ path: '/account' })).toBe('/login')
  })

  it('allows the login page without a token', () => {
    expect(authGuard({ path: '/login' })).toBeUndefined()
  })

  it('allows authenticated routes', () => {
    sessionStorage.setItem('token', 'jwt')
    expect(authGuard({ path: '/' })).toBeUndefined()
  })
})

describe('router redirects', () => {
  it('redirects /chat to home after login', async () => {
    sessionStorage.setItem('token', 'jwt')
    const router = makeRouter()
    await router.push('/chat')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('blocks home when the session is empty', async () => {
    const router = makeRouter()
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
