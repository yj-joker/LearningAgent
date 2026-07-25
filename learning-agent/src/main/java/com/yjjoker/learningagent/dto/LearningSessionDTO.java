package com.yjjoker.learningagent.dto;

import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LearningSessionDTO {

    @NotNull(message = "课程id不能为空")
    @Valid
    private Long courseId;
    @NotNull(message = "学习会话标题不能为空")
    @Valid
    private String sessionTitle;
    private LearningSessionStatusEnum status;
}
