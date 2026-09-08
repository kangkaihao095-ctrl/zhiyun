import { describe, expect, it, vi } from 'vitest'
import { goBackOrFallback, isShellRoot, moduleFallback } from '../src/nav-back.js'

describe('nav-back', () => {
  it('treats sidebar roots as list pages and nested routes as children', () => {
    expect(isShellRoot({ path: '/', query: {} })).toBe(true)
    expect(isShellRoot({ path: '/history', query: {} })).toBe(true)
    expect(isShellRoot({ path: '/billing', query: {} })).toBe(true)
    expect(isShellRoot({ path: '/account', query: {} })).toBe(true)
    expect(isShellRoot({ path: '/manuscripts/11', query: {} })).toBe(false)
    expect(isShellRoot({ path: '/reviews/5', query: {} })).toBe(false)
    expect(isShellRoot({ path: '/billing', query: { order: 'ZY1' } })).toBe(false)
    expect(isShellRoot({ path: '/account', query: { panel: 'profile' } })).toBe(false)
  })

  it('falls back to the module list', () => {
    expect(moduleFallback({ path: '/manuscripts/11' })).toBe('/')
    expect(moduleFallback({ path: '/reviews/5' })).toBe('/history')
    expect(moduleFallback({ path: '/billing' })).toBe('/billing')
    expect(moduleFallback({ path: '/account' })).toBe('/account')
  })

  it('uses router.back when history.state.back exists', () => {
    const router = { back: vi.fn(), replace: vi.fn() }
    expect(goBackOrFallback(router, { path: '/manuscripts/1' }, { back: '/' })).toBe('back')
    expect(router.back).toHaveBeenCalled()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('replaces with the module list when there is no history', () => {
    const router = { back: vi.fn(), replace: vi.fn() }
    expect(goBackOrFallback(router, { path: '/reviews/3' }, { back: null })).toBe('/history')
    expect(router.replace).toHaveBeenCalledWith('/history')
    expect(router.back).not.toHaveBeenCalled()
  })
})
