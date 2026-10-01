package com.yjjoker.learningagent.harness.plan.model;

import lombok.Data;

import java.time.LocalDateTime;

// 保存一个步骤的内容、完成条件和进度，不把“工具调用成功”直接当成步骤完成。
@Data
public class AgentTaskStep {
    // 后端生成的固定编号；以后调换顺序时不更换这个编号。
    private String stepId;
    private String planId;
    // 从 1 开始的执行顺序，和步骤身份分开保存。
    private int position;
    private String description;
    // 学习类步骤描述用户至少能解释或做到什么；允许用户主动继续，但不伪装成已验证掌握。
    private String completionCriteria;
    private AgentTaskStepStatus status;
    // 保存确认方式、简短理由和相关用户原话；完整对话仍留在消息表，不在此重复保存。
    private String resultSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
