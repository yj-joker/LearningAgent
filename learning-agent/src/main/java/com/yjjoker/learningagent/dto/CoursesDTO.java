package com.yjjoker.learningagent.dto;

import lombok.Data;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
public class CoursesDTO {
   @NotBlank(message = "课程名称不能为空")
   @Size(max = 128, message = "课程名称不能超过128个字符")
   private String courseName;

   @NotNull(message = "课程难度不能为空")
   @Min(value = 1, message = "课程难度必须在1到5之间")
   @Max(value = 5, message = "课程难度必须在1到5之间")
   private Long difficultyLevel;

   @Size(max = 2000, message = "学习大纲不能超过2000个字符")
   private String learningOutline;
}
