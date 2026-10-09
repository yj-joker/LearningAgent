package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.vo.CoursesVO;

import java.util.Set;
import java.util.List;

public interface CoursesService {

    // 查询当前用户自己的课程，包含私有、待审核和已发布状态。
    List<CoursesVO> findCurrentUserCourses();

    // 课程编辑详情只允许课程所有者读取，不替代公开课程的查看接口。
    CoursesVO findOwnedCourse(Long courseId);

    CoursesVO findCourse(Long courseId);

    CoursesVO createCourse(CoursesDTO coursesDTO);

    CoursesVO publishCourse(Long courseId);

    CoursesVO passCourse(Long courseId);

    CoursesVO rejectCourse(Long courseId);

    void checkUserOwnsCourse(Long courseId);

    void checkUserOwnsCourses(Set<Long> courseIds);

    void checkUserCanViewCourse(Long courseId);

    void checkUserCanViewCourses(Set<Long> courseIds);
}
