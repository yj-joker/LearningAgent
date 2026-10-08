import { request } from './client'
import type { AgentMode, ApiId, LearningSessionVO, SessionCreatePayload, SessionDisplayMessage } from '@/types/api'

const SESSION_BASE = '/learning-agent/learning'

// 列表以数据库为准，不再从浏览器操作记录推导会话。
export function listSessions() {
  return request<LearningSessionVO[]>(`${SESSION_BASE}/sessions`)
}

// 每次独立会话都获得新的 ID，避免复用上一模式的目标和记忆。
export function createStandaloneSession(sessionTitle: string, mode: 'CHAT' | 'FOCUS') {
  return request<LearningSessionVO>(`${SESSION_BASE}/standalone-session`, {
    method: 'POST', body: JSON.stringify({ sessionTitle, mode }),
  })
}

export function getSessionMessages(sessionId: ApiId, mode?: AgentMode) {
  const query = mode ? `?mode=${mode}` : ''
  return request<SessionDisplayMessage[]>(`${SESSION_BASE}/session/${encodeURIComponent(String(sessionId))}/messages${query}`)
}

// 用户进入课程页后初始化课程快照；重复开始不会重置已有进度。
export function startCourseLearning(sessionId: ApiId) {
  return request<unknown>(`${SESSION_BASE}/course-progress/${encodeURIComponent(String(sessionId))}`, { method: 'POST' })
}

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

export function deleteLearningSession(sessionId: ApiId) {
  return request<void>(`/agent/delete/${encodeURIComponent(String(sessionId))}`, {
    method: 'DELETE',
  })
}
