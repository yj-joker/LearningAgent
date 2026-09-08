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
    const isMultipart = typeof FormData !== 'undefined' && init?.body instanceof FormData
    const headers = {
      Accept: 'application/json',
      ...(isMultipart ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    }
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers,
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

export async function requestBlob(path: string): Promise<{ blob: Blob; filename?: string }> {
  const token = getStoredToken()
  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      headers: {
        Accept: '*/*',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    })
  } catch {
    throw new ApiError('服务暂时不可用，请稍后重试')
  }
  if (!response.ok) {
    throw new ApiError(response.status >= 500 ? '服务暂时不可用，请稍后重试' : '文件下载失败，请检查权限后重试', response.status)
  }
  const disposition = response.headers.get('content-disposition') ?? ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return { blob: await response.blob(), filename: encoded ? decodeURIComponent(encoded) : plain }
}
