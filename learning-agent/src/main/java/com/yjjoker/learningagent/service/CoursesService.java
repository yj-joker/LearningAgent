package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.vo.CoursesVO;

import java.util.Optional;

public interface CoursesService {
    Optional<CoursesVO> findCourseById(Long courseId);
}
