package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

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
    public Boolean isUserCoursesOrPublic(Long userId, Long courseId){
        if(userId==null||courseId==null){
            return false;
        }
        return userId.equals(publisherId) || courseType == CoursesTypeEnum.PUBLIC;
    }
}
