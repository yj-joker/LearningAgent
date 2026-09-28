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
    // 旧申请默认是单条变更；整理申请的 operation 留空，不能冒充单条 UPDATE。
    private MemoryApprovalType approvalType = MemoryApprovalType.CHANGE;
    private MemoryOperation operation;
    private MemoryScope scope;
    private String candidateJson;
    private String targetSnapshotJson;
    // 整理版本用于防重复提案；普通变更不填写，也不影响原有审批。
    private Long snapshotChangeCount;
    private Long snapshotProcessedCount;
    private MemoryApprovalStatus status;
    private String decisionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime decidedAt;
}
