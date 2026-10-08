package com.yjjoker.learningagent.dto;

import com.yjjoker.learningagent.harness.model.AgentMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

// 独立问答或专注会话不需要课程，模式由创建时固定下来。
@Data
public class StandaloneLearningSessionDTO {
    @NotBlank(message = "学习会话标题不能为空")
    @Size(max = 255, message = "学习会话标题不能超过 255 个字符")
    private String sessionTitle;

    @NotNull(message = "会话模式不能为空")
    private AgentMode mode;
}
