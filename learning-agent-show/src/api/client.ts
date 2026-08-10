import type { ApiResult } from '@/types/api'
import { getStoredToken } from '@/utils/authStorage'
import JSONbigFactory from 'json-bigint'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
const JSONbig = JSONbigFactory({ storeAsString: true })

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
    throw new ApiError('服务暂时不可用，请稍后重试')
  }

  const contentType = response.headers.get('content-type') ?? ''
  const rawBody = await response.text().catch(() => '')
  const body = contentType.includes('application/json')
    ? (() => {
        try {
          return JSONbig.parse(rawBody)
        } catch {
          return null
        }
      })()
    : rawBody

  if (!response.ok) {
    const message = response.status >= 500
      ? '服务暂时不可用，请稍后重试'
      : typeof body === 'object' && body
        ? body.message ?? body.detail ?? '请求未完成，请检查后重试'
        : '请求未完成，请检查后重试'
    throw new ApiError(message, response.status)
  }

  const result = body as ApiResult<T>
  if (!result || result.code !== '200') {
    throw new ApiError(result?.message || '服务返回了未知错误', response.status, result?.code)
  }

  return result.data
}
