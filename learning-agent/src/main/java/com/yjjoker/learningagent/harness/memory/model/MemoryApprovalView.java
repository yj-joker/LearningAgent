package com.yjjoker.learningagent.harness.memory.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

// 返回给前端展示的审批申请，不暴露数据库内部快照解析细节。
@Getter
@AllArgsConstructor
public class MemoryApprovalView {
    private Long id;
    private Long sessionId;
    private MemoryApprovalType approvalType;
    private MemoryOperation operation;
    private MemoryScope scope;
    private String candidateJson;
    private MemoryApprovalStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
    private String decisionReason;

    // 从数据库申请对象转换为前端展示对象。
    public static MemoryApprovalView from(MemoryApprovalRequest request) {
        return new MemoryApprovalView(request.getId(), request.getSessionId(), request.getApprovalType(), request.getOperation(),
                request.getScope(), request.getCandidateJson(), request.getStatus(),
                request.getCreatedAt(), request.getDecidedAt(), request.getDecisionReason());
    }
}
