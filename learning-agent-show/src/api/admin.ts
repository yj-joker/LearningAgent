import { request } from './client'
import type { PageResult, UserPageItem, UserPageQuery } from '@/types/api'

export function queryUsers(query: UserPageQuery) {
  const params = new URLSearchParams({
    page: String(query.page),
    size: String(query.size),
  })

  if (query.username?.trim()) params.set('username', query.username.trim())
  if (query.role) params.set('role', query.role)
  if (query.createdAtStart) params.set('createdAtStart', query.createdAtStart)
  if (query.createdAtEnd) params.set('createdAtEnd', query.createdAtEnd)

  return request<PageResult<UserPageItem>>(`/learning-agent/user/pageQuery?${params.toString()}`, {
    method: 'GET',
  })
}
