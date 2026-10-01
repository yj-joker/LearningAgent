package com.yjjoker.learningagent.harness.plan.model;

// 步骤进度与审批状态分开；等待审批仍由现有审批模块管理。
public enum AgentTaskStepStatus {
    PENDING, // 尚未开始；第一阶段创建的步骤统一使用此状态。
    IN_PROGRESS, // 正在执行，但还没有满足完成条件。
    COMPLETED, // 已记录完成结果；后续仍需要按任务要求验证。
    BLOCKED, // 缺少资料、用户回复或其他必要条件。
    CANCELED // 计划调整后不再执行；保留记录，不悄悄删除历史。
}
