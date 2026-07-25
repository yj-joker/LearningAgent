package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.vo.CoursesVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@AllArgsConstructor
public class CoursesServiceImpl implements CoursesService {
    private final CoursesRepository coursesRepository;
    @Override
    public Optional<CoursesVO> findCourseById(Long courseId) {
        Optional<Courses> courses = coursesRepository.findCourseById(courseId);
        if(courses.isEmpty()){
            return Optional.empty();
        }
        CoursesVO coursesVO=new CoursesVO();
        BeanUtils.copyProperties(courses.get(),coursesVO);
        return Optional.of(coursesVO);
    }
}
