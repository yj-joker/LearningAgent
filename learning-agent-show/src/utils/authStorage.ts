import type { UserRole, UserVO } from '@/types/api'

export const AUTH_STORAGE_KEY = 'learning-agent.auth.v1'
export const AUTH_CHANGED_EVENT = 'learning-agent:auth-changed'

export function readRoleFromToken(token: string | null): UserRole | null {
  if (!token) return null
  try {
    const payload = token.split('.')[1]
    if (!payload) return null
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
    const data = JSON.parse(atob(padded)) as { role?: string }
    return data.role === 'ADMIN' || data.role === 'USER' ? data.role : null
  } catch {
    return null
  }
}

export function readStoredUser(): UserVO | null {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as UserVO
    // 浏览器存储可能残留用户信息；没有非空 token 就不能作为已登录身份。
    const token = typeof value?.token === 'string' ? value.token.trim() : null
    return value?.username && token ? { ...value, token, role: value.role ?? readRoleFromToken(token) } : null
  } catch {
    return null
  }
}

export function getStoredToken(): string | null {
  return readStoredUser()?.token ?? null
}

export function storeUser(user: UserVO) {
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(user))
  // storage 事件只通知其他标签页；自定义事件让当前页也同步登录状态。
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT))
}

export function clearStoredUser() {
  localStorage.removeItem(AUTH_STORAGE_KEY)
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT))
}

export function invalidateStoredAuth(requestToken: string | null): boolean {
  const currentToken = getStoredToken()
  // 旧账号的请求可能迟到；只有当前账号仍使用该 token 时才清除它。
  if (currentToken && currentToken !== requestToken) return false
  clearStoredUser()
  return true
}
