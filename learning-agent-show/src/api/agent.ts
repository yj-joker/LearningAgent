import { request } from './client'
import type { AgentChatPayload } from '@/types/api'

export function chatWithAgent(payload: AgentChatPayload) {
  return request<string>('/agent/chat', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
