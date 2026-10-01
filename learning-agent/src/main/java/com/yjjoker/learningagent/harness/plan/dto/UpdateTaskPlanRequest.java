package com.yjjoker.learningagent.harness.plan.dto;

import lombok.Data;

import java.util.List;

// 提交修改后的完整步骤列表，不允许通过这个请求改写用户目标、限制或任务归属。
@Data
public class UpdateTaskPlanRequest {
    // 必须是调用方读取计划时拿到的版本，后端不会自动改成最新版本替它重试。
    private Long expectedVersion;
    // 必须保留全部旧步骤；取消也要留在列表中，列表顺序就是新的执行顺序。
    private List<UpdateTaskStepRequest> steps;
}
