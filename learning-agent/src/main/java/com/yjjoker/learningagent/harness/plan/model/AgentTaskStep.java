package com.yjjoker.learningagent.harness.plan.model;

import lombok.Data;

import java.time.LocalDateTime;

// 保存一个步骤的内容、完成条件和进度，不把“工具调用成功”直接当成步骤完成。
@Data
public class AgentTaskStep {
    // 后端生成的固定编号；以后调换顺序时不更换这个编号。
    private String stepId;
    private String runId;
    // 从 1 开始的执行顺序，和步骤身份分开保存。
    private int position;
    private String description;
    private String completionCriteria;
    private AgentTaskStepStatus status;
    // 记录简短结果、依据或受阻原因；不保存完整工具正文。
    private String resultSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
