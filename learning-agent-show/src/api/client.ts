import type { ApiResult } from '@/types/api'
import { getStoredToken, invalidateStoredAuth } from '@/utils/authStorage'
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

function parseJson(rawBody: string): unknown {
  try { return JSONbig.parse(rawBody) }
  catch { return null }
}

function isAuthenticationFailure(status: number, body: unknown): boolean {
  const result = typeof body === 'object' && body ? body as Partial<ApiResult<unknown>> : null
  const code = String(result?.code ?? '')
  // 权限不足不是登录失效，不应把仍然有效的用户退出。
  if (status === 403 || code === '403') return false
  if (status === 401 || code === '401') return true
  // 当前后端将登录拦截器异常包装为 HTTP 200；只匹配认证异常的确切含义。
  return (code === '404' && result?.message === '用户未登录')
    || (code === '500' && result?.message === '非法访问')
}

function requireToken(): string {
  const token = getStoredToken()
  if (token) return token
  invalidateStoredAuth(null)
  throw new ApiError('请先登录后再继续', 401, '401')
}

function rejectInvalidAuthentication(status: number, body: unknown, token: string | null, isAuthRequest = false) {
  // 登录、注册本来就不携带 token；失败时留在表单里展示错误。
  if (isAuthRequest || !isAuthenticationFailure(status, body)) return
  invalidateStoredAuth(token)
  throw new ApiError('登录状态已失效，请重新登录', status, '401')
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  const pathname = path.split('?')[0]?.replace(/\/+$/, '') ?? path
  const isAuthRequest = pathname.endsWith('/user/login') || pathname.endsWith('/user/register')
  const token = isAuthRequest ? null : requireToken()

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
    ? parseJson(rawBody)
    : rawBody

  rejectInvalidAuthentication(response.status, body, token, isAuthRequest)

  if (!response.ok) {
    const message = response.status >= 500
      ? '服务暂时不可用，请稍后重试'
      : typeof body === 'object' && body
        ? (body as { message?: string; detail?: string }).message ?? (body as { detail?: string }).detail ?? '请求未完成，请检查后重试'
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
  const token = requireToken()
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
  const disposition = response.headers.get('content-disposition') ?? ''
  // 真正的文件会带下载头；没有下载头的 JSON 可能是后端返回的认证异常。
  const body = response.headers.get('content-type')?.includes('application/json') && !disposition
    ? parseJson(await response.clone().text().catch(() => '')) : null
  rejectInvalidAuthentication(response.status, body, token)
  if (!response.ok) {
    throw new ApiError(response.status >= 500 ? '服务暂时不可用，请稍后重试' : '文件下载失败，请检查权限后重试', response.status)
  }
  if (body && typeof body === 'object' && 'code' in body) {
    const result = body as ApiResult<unknown>
    if (result.code !== '200') throw new ApiError(result.message || '文件下载失败，请稍后重试', response.status, result.code)
  }
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return { blob: await response.blob(), filename: encoded ? decodeURIComponent(encoded) : plain }
}
