import { request } from './client'
import type { ApiId, KnowledgePointPayload, KnowledgePointVO } from '@/types/api'

const KNOWLEDGE_POINT_BASE = '/knowledgePoints'

export function createKnowledgePoints(payload: KnowledgePointPayload[]) {
  return request<KnowledgePointVO[]>(`${KNOWLEDGE_POINT_BASE}/createKnowledgePoint`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function getKnowledgePointsByChapterId(chapterId: ApiId) {
  return request<KnowledgePointVO[]>(`${KNOWLEDGE_POINT_BASE}/chapter/${chapterId}`)
}

export function updateKnowledgePoints(payload: KnowledgePointPayload[]) {
  return request<KnowledgePointVO[]>(`${KNOWLEDGE_POINT_BASE}/updateKnowledgePoints`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function deleteKnowledgePointsByIds(ids: ApiId[]) {
  return request<void>(`${KNOWLEDGE_POINT_BASE}/${ids.join(',')}`, {
    method: 'DELETE',
  })
}
