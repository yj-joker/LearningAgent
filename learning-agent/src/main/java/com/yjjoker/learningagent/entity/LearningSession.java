package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;
@Data
public class LearningSession {
     private Long id;
     // 课程会话保留课程编号；独立问答或专注会话的课程编号为空。
     private Long courseId;
     // 独立会话的模式创建后固定，切换使用新的会话编号。
     private AgentMode mode;
     private Long userId;
     private String sessionTitle;
     private LearningSessionStatusEnum status;
     private LocalDateTime createdAt;
     private LocalDateTime updatedAt;
}
