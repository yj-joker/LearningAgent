package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import lombok.Data;

@Data
public class CoursesVO {
    private String courseName;
    private String publisherName;
    private Long difficultyLevel;
    private String learningOutline;
    private CoursesTypeEnum courseType;
}
