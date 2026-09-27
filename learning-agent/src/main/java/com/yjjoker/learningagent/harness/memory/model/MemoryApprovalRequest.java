package com.yjjoker.learningagent.harness.memory.model;

import lombok.Data;

import java.time.LocalDateTime;

// 保存一份等待用户确认的记忆变更申请。
// candidateJson 和 targetSnapshotJson 保存审批时看到的内容，批准时用于快照校验。
@Data
public class MemoryApprovalRequest {
    private Long id;
    private Long userId;
    private Long sessionId;
    private MemoryOperation operation;
    private MemoryScope scope;
    private String candidateJson;
    private String targetSnapshotJson;
    private MemoryApprovalStatus status;
    private String decisionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime decidedAt;
}
