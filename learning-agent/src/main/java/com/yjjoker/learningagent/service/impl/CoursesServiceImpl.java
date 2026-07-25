package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.exception.*;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.CoursesVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@AllArgsConstructor
@Slf4j
public class CoursesServiceImpl implements CoursesService {
    private final CoursesRepository coursesRepository;

    //根据id查询课程
    @Override
    public CoursesVO findCourse(Long courseId) {
    Courses courses = coursesRepository.findCourseById(courseId);
        CoursesVO coursesVO = new CoursesVO();
        BeanUtils.copyProperties(courses, coursesVO);
        return coursesVO;
    }

    //创建课程
    @Override
    public CoursesVO createCourse(CoursesDTO coursesDTO) {
        Courses courses = new Courses();
        BeanUtils.copyProperties(coursesDTO, courses);
        courses.setUserId(BaseContext.getCurrentId());
        courses.setPublisherId(BaseContext.getCurrentId());
        courses.setCreatedAt(LocalDateTime.now());
        courses.setUpdatedAt(LocalDateTime.now());
        coursesRepository.createCourse(courses);
        CoursesVO coursesVO = new CoursesVO();
        BeanUtils.copyProperties(courses, coursesVO);
        return coursesVO;
    }

    //发布课程
    @Override
    public CoursesVO publishCourse(Long courseId) {
        //获取课程
        Courses courses = coursesRepository.findCourseById(courseId);
        if (courses== null) {
            throw new NotFountException("课程不存在");
        }
        if (!courses.getUserId().equals(BaseContext.getCurrentId())) {
            throw new ViolationOperationException("非法操作");
        }
        if(courses.getCourseType() == CoursesTypeEnum.PUBLIC){
            throw new CreateStatusException("课程已发布");
        }
        courses.setCourseType(CoursesTypeEnum.PUBLIC);
        courses.setPublisherId(BaseContext.getCurrentId());
        courses.setUpdatedAt(LocalDateTime.now());
        int affectedRows = coursesRepository.publishCourse(courses);
        if (affectedRows!=1) {
            throw new CreateErrorException("系统保存课程出错，稍后再试");
        }
        CoursesVO coursesVO = new CoursesVO();
        BeanUtils.copyProperties(courses, coursesVO);
        log.info("发布课程成功");
        return coursesVO;
    }
}
