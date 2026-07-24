package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;

import java.time.LocalDateTime;

public class LearningSession {
     private Long id;
     private Long courseId;
     private Long userId;
     private String sessionTitle;
     private LearningSessionStatusEnum status;
     private LocalDateTime createdAt;
     private LocalDateTime updatedAt;
}
