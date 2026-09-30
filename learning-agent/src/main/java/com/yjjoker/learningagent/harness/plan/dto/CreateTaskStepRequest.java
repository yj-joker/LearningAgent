package com.yjjoker.learningagent.harness.plan.dto;

import lombok.Data;

// 创建步骤只需要说明做什么，以及怎样才算完成。
@Data
public class CreateTaskStepRequest {
    private String description;
    private String completionCriteria;
}
