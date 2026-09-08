package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.projectenum.OwnerType;
import com.yjjoker.learningagent.projectenum.VisibilityEnum;
import lombok.Data;

@Data
public class UploadFileVerifyMessage {
    private Long userId;//知识库所属的课程是谁
    private OwnerType ownerType;//知识库是否是系统的
    private VisibilityEnum visibilityEnum;//知识库的可见性
    private CoursesTypeEnum coursesTypeEnum;//课程是否可操作
}
