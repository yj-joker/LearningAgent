import type { ApiResult } from '@/types/api'
import { getStoredToken } from '@/utils/authStorage'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  const isAuthRequest = path.endsWith('/user/login') || path.endsWith('/user/register')
  const token = isAuthRequest ? null : getStoredToken()

  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...init?.headers,
      },
    })
  } catch {
    throw new ApiError('无法连接后端服务，请确认 Spring Boot 已在 8080 端口启动')
  }

  const contentType = response.headers.get('content-type') ?? ''
  const body = contentType.includes('application/json')
    ? await response.json().catch(() => null)
    : await response.text().catch(() => '')

  if (!response.ok) {
    const message = typeof body === 'object' && body
      ? body.message ?? body.detail ?? `请求失败（HTTP ${response.status}）`
      : `请求失败（HTTP ${response.status}）`
    throw new ApiError(message, response.status)
  }

  const result = body as ApiResult<T>
  if (!result || result.code !== '200') {
    throw new ApiError(result?.message || '后端返回了未知错误', response.status, result?.code)
  }

  return result.data
}

export type BackendState = 'online' | 'degraded' | 'offline'

export async function checkBackend(): Promise<BackendState> {
  try {
    const response = await fetch(`${API_BASE_URL}/v3/api-docs`, {
      headers: {
        Accept: 'application/json',
        ...(getStoredToken() ? { Authorization: `Bearer ${getStoredToken()}` } : {}),
      },
    })
    return response.ok ? 'online' : 'degraded'
  } catch {
    return 'offline'
  }
}
