package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Objects;

@Data
public class Courses {
    private Long id;
    private Long userId;
    private String courseName;
    private Long difficultyLevel;
    private Long publisherId;
    private String learningOutline;
    private CoursesTypeEnum courseType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
