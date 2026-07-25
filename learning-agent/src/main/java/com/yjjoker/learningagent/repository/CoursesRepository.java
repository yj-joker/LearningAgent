package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.vo.CoursesVO;

import java.util.Optional;

public interface CoursesRepository {
    Optional<Courses> findCourseById(Long id);
}
