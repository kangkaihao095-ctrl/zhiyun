/** @param {{ path: string }} to */
export function isAuthPublic(path) {
  return path === '/login' || path === '/ops/login'
}

export function isOpsPath(path) {
  return path === '/ops' || (path.startsWith('/ops/') && path !== '/ops/login')
}

export function authGuard(to) {
  const token = sessionStorage.getItem('token')
  if (isAuthPublic(to.path)) {
    return
  }
  if (!token) {
    return isOpsPath(to.path) ? '/ops/login' : '/login'
  }
}
