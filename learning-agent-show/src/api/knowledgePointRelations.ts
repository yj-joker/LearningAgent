import { request } from './client'
import type { ApiId, KnowledgePointRelationPayload, KnowledgePointRelationVO } from '@/types/api'

const KNOWLEDGE_POINT_RELATION_BASE = '/knowledgePointRelations'

export function createKnowledgePointRelation(payload: KnowledgePointRelationPayload) {
  return request<KnowledgePointRelationVO>(`${KNOWLEDGE_POINT_RELATION_BASE}/createKnowledgePointRelations`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/**
 * 仅供管理员使用。普通用户界面不提供关系删除入口，避免把管理员能力暴露到用户端。
 */
export function deleteKnowledgePointRelationsByIds(ids: ApiId[]) {
  return request<void>(`${KNOWLEDGE_POINT_RELATION_BASE}/deleteKnowledgePointRelations/${ids.join(',')}`, {
    method: 'DELETE',
  })
}
