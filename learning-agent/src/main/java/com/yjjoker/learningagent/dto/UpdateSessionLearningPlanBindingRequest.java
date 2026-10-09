package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// 提交会话的长期计划引用；空值仅允许未关联会话保持不关联，不能解除已保存的关联。
@Getter
@Setter
public class UpdateSessionLearningPlanBindingRequest {
    // 只允许绑定短引用，不接受任意长文本。
    @Size(max = 64, message = "学习计划引用不能超过 64 个字符")
    private String draftRef;

    // 独立关联版本用于拒绝页面持有的旧选择。
    @NotNull(message = "关联版本不能为空")
    @Min(value = 0, message = "关联版本不能小于 0")
    private Long expectedBindingVersion;
}
