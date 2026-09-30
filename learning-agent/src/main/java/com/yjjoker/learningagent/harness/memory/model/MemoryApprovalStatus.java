package com.yjjoker.learningagent.harness.memory.model;

// 记录记忆审批申请当前所处的状态。
public enum MemoryApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    // 等待期间原记忆或整理进度已变化，不执行旧方案；不是按时间自动过期。
    STALE
}
