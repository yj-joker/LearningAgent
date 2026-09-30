package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.harness.approval.ToolApprovalRequest;
import com.yjjoker.learningagent.harness.model.AgentRunStatus;
import lombok.Getter;

import java.util.List;

// 聊天接口返回结构化状态，前端不用从回答文字中猜测是否需要审批。
@Getter
public class AgentRunResult {
    // runId 标识整个逻辑任务；同一任务可以经历多次审批批次。
    private final String runId;
    private final int batchNumber;
    private final AgentRunStatus status;
    // 普通回答来自模型；待审批提示由后端生成，不额外请求模型。
    private final String answer;
    private final List<ToolApprovalRequest> approvals;

    // 固定返回内容，避免后续修改列表改变已经构造好的响应。
    private AgentRunResult(String runId, int batchNumber, AgentRunStatus status, String answer,
                           List<ToolApprovalRequest> approvals) {
        this.runId = runId;
        this.batchNumber = batchNumber;
        this.status = status;
        this.answer = answer;
        this.approvals = List.copyOf(approvals);
    }

    // 正常回答不附带主循环的待审批申请。
    public static AgentRunResult completed(String runId, String answer) {
        return new AgentRunResult(runId, 0, AgentRunStatus.COMPLETED, answer, List.of());
    }

    // 保存申请成功后才能返回等待状态，不能把内存中的草稿说成已经提交。
    public static AgentRunResult state(String runId, int batchNumber, AgentRunStatus status,
                                       String answer, List<ToolApprovalRequest> approvals) {
        return new AgentRunResult(runId, batchNumber, status, answer, approvals);
    }
}
