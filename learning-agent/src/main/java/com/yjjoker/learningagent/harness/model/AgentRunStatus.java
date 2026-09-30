package com.yjjoker.learningagent.harness.model;

// 区分正常结束和等待审批；审批处理完不代表原任务已经恢复执行。
public enum AgentRunStatus {
    COMPLETED, // 任务已结束并有答复，不代表每个工具都执行成功。
    WAITING_APPROVAL, // 等待用户选择，本批工具还没有执行。
    APPROVAL_RESOLVED, // 本批都已作出决定，可能有拒绝，尚未恢复。
    RUNNING, // 一个恢复请求已经取得执行权，其他请求不能重复执行。
    FAILED // 出现系统异常，不能自动重跑可能已经产生副作用的工具。
}
