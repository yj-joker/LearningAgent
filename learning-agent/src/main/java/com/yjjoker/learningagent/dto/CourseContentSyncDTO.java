package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

// 用户确认的是读取时看到的课程和进度，不能同步之后偷偷改过的内容。
@Data
public class CourseContentSyncDTO {
    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}", message = "课程同步凭据格式不正确")
    private String expectedSyncToken;
}
