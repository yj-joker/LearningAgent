package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LearningSessionVO {
      private Long id;
      private String sessionTitle;
      private LearningSessionStatusEnum sessionStatus;
      private LocalDateTime createAt;
      private LocalDateTime updateAt;
}
