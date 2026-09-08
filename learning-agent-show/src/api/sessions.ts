import { request } from './client'
import type { ApiId, LearningSessionVO, SessionCreatePayload } from '@/types/api'

const SESSION_BASE = '/learning-agent/learning'

export function createSession(payload: SessionCreatePayload) {
  return request<LearningSessionVO>(`${SESSION_BASE}/session`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function completeSession(sessionId: ApiId) {
  return request<LearningSessionVO>(`${SESSION_BASE}/session/completed/${encodeURIComponent(String(sessionId))}`, {
    method: 'PUT',
  })
}
