package com.yjjoker.learningagent.harness.plan.dto;

import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import lombok.Data;

// 模型只申请状态变化，不能顺便改写步骤正文、完成条件或数据库编号。
@Data
public class StepProgressChange {
    private String stepRef;
    private AgentTaskStepStatus status;
    // 简短说明做了什么、为什么申请变更；这不是知识正确性证明。
    private String reason;
    // 完成时区分对话依据与用户主动继续；其他状态使用 NOT_APPLICABLE。
    private String completionBasis;
    // 原样引用上下文中的用户话语，后端只验证引用存在，不判断答案是否正确。
    private String userEvidence;
}
