import type { UserRole, UserVO } from '@/types/api'

export const AUTH_STORAGE_KEY = 'learning-agent.auth.v1'

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
    return value?.username ? { ...value, role: value.role ?? readRoleFromToken(value.token) } : null
  } catch {
    return null
  }
}

export function getStoredToken(): string | null {
  return readStoredUser()?.token ?? null
}

export function storeUser(user: UserVO) {
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(user))
}

export function clearStoredUser() {
  localStorage.removeItem(AUTH_STORAGE_KEY)
}
