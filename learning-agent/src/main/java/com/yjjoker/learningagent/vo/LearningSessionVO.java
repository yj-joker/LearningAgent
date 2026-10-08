package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LearningSessionVO {
      private Long id;
      private String sessionTitle;
      private LearningSessionStatusEnum sessionStatus;
      private Long courseId;
      private String courseName;
      private AgentMode mode;
      private LocalDateTime createdAt;
      private LocalDateTime updatedAt;
      // 保留旧字段名，已有前端仍可读取；新页面统一使用 createdAt/updatedAt。
      private LocalDateTime createAt;
      private LocalDateTime updateAt;
}
