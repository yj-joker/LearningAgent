package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// 接收草案中的一个步骤；修改时 stepRef 为空表示新增步骤。
@Data
public class LearningPlanDraftStepRequest {
    // 更新已有步骤时复制；创建新步骤时为空。
    private String stepRef;

    // 阶段内容。
    @NotBlank(message = "草案步骤不能为空")
    @Size(max = 1000, message = "草案步骤不能超过 1000 个字符")
    private String description;

    // 阶段完成条件。
    @NotBlank(message = "步骤完成条件不能为空")
    @Size(max = 1000, message = "步骤完成条件不能超过 1000 个字符")
    private String completionCriteria;
}
