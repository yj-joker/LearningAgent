import { request } from './client'
import type {
  LearningPlanDraft,
  LearningPlanDraftCreatePayload,
  LearningPlanDraftUpdatePayload,
} from '@/types/api'

const DRAFT_BASE = '/learning-agent/learning-plans/drafts'

// 读取当前用户的全部未生效草案。
export function listLearningPlanDrafts() {
  return request<LearningPlanDraft[]>(DRAFT_BASE)
}

// 读取一份草案的完整步骤。
export function getLearningPlanDraft(draftRef: string) {
  return request<LearningPlanDraft>(`${DRAFT_BASE}/${encodeURIComponent(draftRef)}`)
}

// 手动创建草案；后端固定 source=MANUAL。
export function createLearningPlanDraft(payload: LearningPlanDraftCreatePayload) {
  return request<LearningPlanDraft>(DRAFT_BASE, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

// 按 expectedVersion 更新同一份草案，版本冲突由后端拒绝。
export function updateLearningPlanDraft(draftRef: string, payload: LearningPlanDraftUpdatePayload) {
  return request<LearningPlanDraft>(`${DRAFT_BASE}/${encodeURIComponent(draftRef)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}
