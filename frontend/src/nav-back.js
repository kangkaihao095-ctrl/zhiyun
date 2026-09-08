/** 侧栏四个根列表：论文 / 审校 / 订单 / 设置入口。带 query 的子页不算根。 */
export function isShellRoot(route) {
  const path = route?.path || ''
  const q = route?.query || {}
  if (path === '/') return true
  if (path === '/history') return true
  if (path === '/billing' && !q.order) return true
  if (path === '/account' && !q.panel) return true
  return false
}

export function moduleFallback(route) {
  const path = route?.path || ''
  if (path.startsWith('/manuscripts')) return '/'
  if (path.startsWith('/reviews')) return '/history'
  if (path === '/billing' || path.startsWith('/orders')) return '/billing'
  if (path === '/account' || path.startsWith('/models')) return '/account'
  return '/'
}

export function hasRouterHistory(historyState) {
  return historyState != null && historyState.back != null
}

/** 有浏览器/路由历史就 back，否则回到该模块列表。 */
export function goBackOrFallback(router, route, historyState) {
  const state = historyState !== undefined
    ? historyState
    : (typeof window !== 'undefined' ? window.history.state : null)
  if (hasRouterHistory(state)) {
    router.back()
    return 'back'
  }
  const dest = moduleFallback(route)
  router.replace(dest)
  return dest
}
