import { request } from './client'
import type {
  LearningPlanDraft,
  LearningPlanDraftCreatePayload,
  LearningPlanDraftUpdatePayload,
  SessionLearningPlanBinding,
} from '@/types/api'

const DRAFT_BASE = '/learning-agent/learning-plans/drafts'

// 读取当前用户的全部未生效计划。
export function listLearningPlanDrafts() {
  return request<LearningPlanDraft[]>(DRAFT_BASE)
}

// 读取一份计划的完整步骤。
export function getLearningPlanDraft(draftRef: string) {
  return request<LearningPlanDraft>(`${DRAFT_BASE}/${encodeURIComponent(draftRef)}`)
}

// 手动创建计划；后端固定 source=MANUAL。
export function createLearningPlanDraft(payload: LearningPlanDraftCreatePayload) {
  return request<LearningPlanDraft>(DRAFT_BASE, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

// 按 expectedVersion 更新同一份计划，版本冲突由后端拒绝。
export function updateLearningPlanDraft(draftRef: string, payload: LearningPlanDraftUpdatePayload) {
  return request<LearningPlanDraft>(`${DRAFT_BASE}/${encodeURIComponent(draftRef)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

// 用户明确确认后把同一份计划切换为正式计划。
export function activateLearningPlanDraft(draftRef: string, expectedVersion: number) {
  return request<LearningPlanDraft>(`${DRAFT_BASE}/${encodeURIComponent(draftRef)}/activate`, {
    method: 'POST',
    body: JSON.stringify({ expectedVersion }),
  })
}

// 读取会话当前关联的长期学习计划。
export function getSessionLearningPlanBinding(sessionId: string) {
  return request<SessionLearningPlanBinding>(`/agent/sessions/${encodeURIComponent(sessionId)}/learning-plan`)
}

// 保存首次关联或重复确认同一计划；后端拒绝更换和解除，并校验 bindingVersion。
export function updateSessionLearningPlanBinding(sessionId: string, draftRef: string | null,
                                                 expectedBindingVersion: number) {
  return request<SessionLearningPlanBinding>(`/agent/sessions/${encodeURIComponent(sessionId)}/learning-plan`, {
    method: 'PUT',
    body: JSON.stringify({ draftRef, expectedBindingVersion }),
  })
}
