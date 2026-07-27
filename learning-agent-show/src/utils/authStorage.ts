import type { UserVO } from '@/types/api'

export const AUTH_STORAGE_KEY = 'learning-agent.auth.v1'

export function readStoredUser(): UserVO | null {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as UserVO
    return value?.username ? value : null
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
