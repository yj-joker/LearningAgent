package com.yjjoker.learningagent.harness.plan.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 会话内持久保存的目标与步骤；多次执行可继续同一计划，不复制聊天记录。
// TODO 学习计划：另行管理可跨会话使用的学习安排；它不是这里一次执行的短计划，也不等同于记忆。
// TODO 学习计划允许用户创建，也允许 Agent 通过工具提出创建或修改；具体数据模型后续讨论。
@Data
public class AgentTaskPlan {
    // 计划编号独立于 runId；切换回来仍使用原编号和原步骤。
    private String planId;
    // 会话内单调递增的编号，模型使用 goal-1 等引用；已分配编号不复用。
    private Integer goalNumber;
    private Long userId;
    private Long sessionId;
    private String goal;
    private String constraints;
    // 新计划从 1 开始；更新步骤和恢复审批时，用它拒绝旧快照。
    private long version;
    // 保存创建短期任务时绑定的长期计划引用。
    private String learningPlanDraftRef;
    // 保存创建短期任务时绑定的长期阶段引用。
    private String learningPlanStageRef;
    // 保存绑定时看到的长期计划数据库版本。
    private long learningPlanVersion;
    // 保存绑定时看到的长期计划语义版本。
    private long learningPlanSemanticVersion;
    // 保存本次任务属于当前阶段、其他阶段还是计划外。
    private LearningPlanTaskScope learningPlanScope = LearningPlanTaskScope.OUT_OF_PLAN;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // 步骤单独存表，读取时按 position 组装，不在计划表重复存 JSON。
    private List<AgentTaskStep> steps = new ArrayList<>();
}
