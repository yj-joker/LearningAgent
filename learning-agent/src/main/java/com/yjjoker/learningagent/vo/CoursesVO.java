package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CoursesVO {
    private Long id;
    private String courseName;
    private Long publisherId;
    private Long difficultyLevel;
    private String learningOutline;
    private CoursesTypeEnum courseType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
