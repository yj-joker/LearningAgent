import { request } from './client'
import type { LearningSessionVO, SessionCreatePayload } from '@/types/api'

const SESSION_BASE = '/learning-agent/learning'

export function createSession(payload: SessionCreatePayload) {
  return request<LearningSessionVO>(`${SESSION_BASE}/session`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
