import { request } from './client'
import type { ApiId, KnowledgePointPayload, KnowledgePointVO } from '@/types/api'

const KNOWLEDGE_POINT_BASE = '/knowledgePoints'

type PointResponse = Omit<KnowledgePointVO, 'id'> & { id: ApiId }

// 小编号可能返回数字，大编号返回字符串；页面始终以字符串匹配选中的知识点。
function normalizePoints(points: PointResponse[]): KnowledgePointVO[] {
  return points.map(point => ({ ...point, id: String(point.id) }))
}

export async function createKnowledgePoints(payload: KnowledgePointPayload[]) {
  return normalizePoints(await request<PointResponse[]>(`${KNOWLEDGE_POINT_BASE}/createKnowledgePoint`, {
    method: 'POST',
    body: JSON.stringify(payload),
  }))
}

export async function getKnowledgePointsByChapterId(chapterId: ApiId) {
  return normalizePoints(await request<PointResponse[]>(`${KNOWLEDGE_POINT_BASE}/chapter/${chapterId}`))
}

export async function getCoursePrerequisiteKnowledgePoints(courseId: ApiId) {
  return normalizePoints(await request<PointResponse[]>(`${KNOWLEDGE_POINT_BASE}/getPrerequisiteKnowledgePoints/${courseId}`))
}

export async function getCourseConfusableKnowledgePoints(courseId: ApiId) {
  return normalizePoints(await request<PointResponse[]>(`${KNOWLEDGE_POINT_BASE}/getConfusableKnowledgePoints/${courseId}`))
}

export async function updateKnowledgePoints(payload: KnowledgePointPayload[]) {
  return normalizePoints(await request<PointResponse[]>(`${KNOWLEDGE_POINT_BASE}/updateKnowledgePoints`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  }))
}

export function deleteKnowledgePointsByIds(ids: ApiId[]) {
  return request<void>(`${KNOWLEDGE_POINT_BASE}/${ids.join(',')}`, {
    method: 'DELETE',
  })
}
