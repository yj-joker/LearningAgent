package com.yjjoker.learningagent.harness.plan.dto;

import lombok.Data;
import com.yjjoker.learningagent.harness.plan.model.LearningPlanTaskScope;

import java.util.List;

// 只接收计划内容；用户、会话和 runId 由服务调用方的可信上下文提供。
@Data
public class CreateTaskPlanRequest {
    private String goal;
    // 没有额外限制时允许不填，服务会统一保存为 null。
    private String constraints;
    // 列表顺序就是初始执行顺序，不能传入已完成状态或自定步骤编号。
    private List<CreateTaskStepRequest> steps;
    // 模型提供归属和阶段引用；真实计划引用与版本由后端校验后补齐。
    private LearningPlanTaskScope learningPlanScope;
    private String learningPlanStageRef;
    private String learningPlanDraftRef;
    private long learningPlanVersion;
    private long learningPlanSemanticVersion;
}
