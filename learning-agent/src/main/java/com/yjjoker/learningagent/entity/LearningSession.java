package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;
@Data
public class LearningSession {
     private Long id;
     private Long courseId;
     private Long userId;
     private String sessionTitle;
     private LearningSessionStatusEnum status;
     private LocalDateTime createdAt;
     private LocalDateTime updatedAt;
}
