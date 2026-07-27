import { request } from './client'
import type { UserCredentials, UserVO } from '@/types/api'

const USER_BASE = '/learning-agent/user'

export function registerUser(payload: UserCredentials) {
  return request<UserVO>(`${USER_BASE}/register`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function loginUser(payload: UserCredentials) {
  return request<UserVO>(`${USER_BASE}/login`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
