/** @param {{ path: string }} to */
export function authGuard(to) {
  const token = sessionStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    return '/login'
  }
}
