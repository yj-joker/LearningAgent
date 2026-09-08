import { request } from './client'
import type { KnowledgeBaseCreatePayload, KnowledgeBaseVO } from '@/types/api'

export function createKnowledgeBase(payload: KnowledgeBaseCreatePayload) {
  return request<KnowledgeBaseVO>('/knowledgeBase/add', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
