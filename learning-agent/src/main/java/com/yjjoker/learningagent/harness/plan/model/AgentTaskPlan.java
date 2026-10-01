package com.yjjoker.learningagent.harness.plan.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 一次任务的目标和限制；审批恢复沿用 runId，不复制聊天记录或审批检查点。
@Data
public class AgentTaskPlan {
    // 使用 Harness 已有的任务编号；没有审批的任务也可以独立保存计划。
    private String runId;
    private Long userId;
    private Long sessionId;
    private String goal;
    private String constraints;
    // 新计划从 1 开始；下一阶段修改时，用它拒绝基于旧计划的覆盖。
    private long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // 步骤单独存表，读取时按 position 组装，不在计划表重复存 JSON。
    private List<AgentTaskStep> steps = new ArrayList<>();
}
