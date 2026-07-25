package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.vo.CoursesVO;
import jakarta.validation.Valid;


public interface CoursesService {

    CoursesVO findCourse(Long courseId);

    CoursesVO createCourse(CoursesDTO coursesDTO);

    CoursesVO publishCourse(@Valid Long courseId);
}
