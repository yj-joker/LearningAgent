package com.yjjoker.learningagent.projectenum;

//课程审核状态枚举
public enum CoursesTypeEnum {
    PRIVATE,//课程私有，允许课程作者编辑或发布
    PENDING,//课程待审核
    PUBLISHED//课程已发布，对其他用户可见
}
