package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.vo.CoursesVO;

import java.util.Set;

public interface CoursesService {

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
