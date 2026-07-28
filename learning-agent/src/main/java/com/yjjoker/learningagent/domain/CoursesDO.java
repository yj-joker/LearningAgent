package com.yjjoker.learningagent.domain;

import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.exception.CourseStatusException;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;

import java.util.Objects;

public class CoursesDO {
    //课程发布，只能由课程创建者进行操作
    public void publishCourse(Courses courses, Long currentUserId) {
        if (!Objects.equals(courses.getUserId(), currentUserId)) {
            throw new ViolationOperationException("非法操作");
        }
        transition(courses, CoursesTypeEnum.PRIVATE, CoursesTypeEnum.PENDING);
    }

    //课程审核通过，管理员操作
    public void passCourse(Courses courses) {
        transition(courses,
                CoursesTypeEnum.PENDING,
                CoursesTypeEnum.PUBLISHED);
    }

    //课程驳回或下架，管理员操作
    public void rejectCourse(Courses courses) {
        CoursesTypeEnum currentStatus = courses.getCourseType();
        if (currentStatus != CoursesTypeEnum.PENDING && currentStatus != CoursesTypeEnum.PUBLISHED) {
            throw new CourseStatusException("当前课程状态不允许驳回或下架");
        }
        courses.setCourseType(CoursesTypeEnum.PRIVATE);
    }

    //课程状态转换
    private void transition(Courses courses, CoursesTypeEnum expectedStatus,
                                     CoursesTypeEnum targetStatus) {
        if (courses.getCourseType() != expectedStatus) {
            throw new CourseStatusException("当前课程状态不允许该操作");
        }
        courses.setCourseType(targetStatus);
    }

    //课程创建者或课程发布者可操作
    public void userCoursesOrPublished(Courses courses, Long currentUserId) {
        if (Objects.equals(courses.getUserId(), currentUserId) || courses.getCourseType() == CoursesTypeEnum.PUBLISHED) {
            return;
        }
        throw new CreateErrorException("非法创建");
    }
}
