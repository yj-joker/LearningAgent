package com.yjjoker.learningagent.dto;

import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import lombok.Data;
import lombok.NonNull;

@Data
public class CoursesDTO {
   @NonNull
   private String courseName;
   private Long difficultyLevel;
   private String learningOutline;
   @NonNull
   private CoursesTypeEnum courseType;

}
