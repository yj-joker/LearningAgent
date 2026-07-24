package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import lombok.Data;

@Data
public class LearningSessionVO {
      private String sessionTitle;
      private LearningSessionStatusEnum sessionStatus;
}
