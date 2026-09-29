import { request } from './client'
import type { AgentChatPayload, AgentRunResult, MemoryApprovalView } from '@/types/api'

export function chatWithAgent(payload: AgentChatPayload) {
  // 接口现在同时返回回答、运行状态和审批列表。
  return request<AgentRunResult>('/agent/chat', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

// 刷新只查询后端状态，不让模型再次解释审批申请。
export function getAgentRun(runId: string) {
  return request<AgentRunResult>(`/agent/runs/${encodeURIComponent(runId)}`)
}

// 按会话找回未完成任务，即使之前的 HTTP 响应丢失也不必重复发问。
export function getActiveAgentRun(sessionId: string) {
  return request<AgentRunResult | null>(`/agent/runs?sessionId=${encodeURIComponent(sessionId)}`)
}

// 只提交对原调用的决定，业务参数始终从后端检查点读取。
export function decideAgentTool(runId: string, batchNumber: number, toolCallId: string, approved: boolean) {
  return request<AgentRunResult>(`/agent/runs/${encodeURIComponent(runId)}/approvals/${batchNumber}/${encodeURIComponent(toolCallId)}`, {
    method: 'POST', body: JSON.stringify({ approved }),
  })
}

// 全批决定齐备后继续原任务，不重新提交用户问题。
export function resumeAgentRun(runId: string) {
  return request<AgentRunResult>(`/agent/runs/${encodeURIComponent(runId)}/resume`, { method: 'POST' })
}

// 通知只提示刷新，审批正文仍由认证过的 HTTP 请求读取。
export function getMemoryApprovals() {
  return request<MemoryApprovalView[]>('/agent/memory-approvals')
}

// 用户点击后才提交决定；WebSocket 不会自动批准或继续任务。
export function decideMemoryApproval(id: string, approved: boolean) {
  return request<MemoryApprovalView>('/agent/memory-approvals/' + encodeURIComponent(id)
    + (approved ? '/approve' : '/reject'), { method: 'POST' })
}
