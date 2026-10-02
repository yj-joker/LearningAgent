package com.yjjoker.learningagent.harness.plan.dto;

import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import lombok.Data;

// 对外展示一个步骤的真实状态，不暴露数据库内部字段和时间。
@Data
public class SessionGoalStepProgress {
    private String stepRef;
    private int position;
    private String description;
    private String completionCriteria;
    private AgentTaskStepStatus status;
    private String resultSummary;

    // 从当前计划步骤生成只读展示对象。
    public static SessionGoalStepProgress from(String stepRef, AgentTaskStep step) {
        SessionGoalStepProgress progress = new SessionGoalStepProgress();
        progress.setStepRef(stepRef);
        progress.setPosition(step.getPosition());
        progress.setDescription(step.getDescription());
        progress.setCompletionCriteria(step.getCompletionCriteria());
        progress.setStatus(step.getStatus());
        progress.setResultSummary(step.getResultSummary());
        return progress;
    }
}
