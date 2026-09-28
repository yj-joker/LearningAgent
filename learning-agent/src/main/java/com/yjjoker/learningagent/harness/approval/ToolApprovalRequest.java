package com.yjjoker.learningagent.harness.approval;

import lombok.Data;

// 一次具体工具调用的审批；只确认原参数，批准不等于工具已经执行。
@Data
public class ToolApprovalRequest {
    private String runId;
    // 同一个任务可能多次暂停，批次号区分每次审批，旧批准不能复用。
    private int batchNumber;
    private String toolCallId;
    private String toolName;
    private String arguments;
    private String reason;
    // PENDING、APPROVED、REJECTED；执行结果放在工具消息中，不混进审批状态。
    private String status;
    private String decisionReason;
}

