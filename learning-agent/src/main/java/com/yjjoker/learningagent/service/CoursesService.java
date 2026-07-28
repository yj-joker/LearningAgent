package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.vo.CoursesVO;

public interface CoursesService {

    CoursesVO findCourse(Long courseId);

    CoursesVO createCourse(CoursesDTO coursesDTO);

    CoursesVO publishCourse(Long courseId);

    CoursesVO passCourse(Long courseId);

    CoursesVO rejectCourse(Long courseId);
}
