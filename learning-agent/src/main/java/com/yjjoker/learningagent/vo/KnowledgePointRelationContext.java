package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import lombok.Data;

/**
 * 创建知识点关系时使用的查询投影，同时保存关系两端的课程归属、所有者和可见状态。
 */
@Data
public class KnowledgePointRelationContext {
    private Long fromPointId;
    private Long fromCourseId;
    private Long fromCourseOwnerId;
    private CoursesTypeEnum fromCourseType;

    private Long toPointId;
    private Long toCourseId;
    private Long toCourseOwnerId;
    private CoursesTypeEnum toCourseType;
}
