package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.CreateStatusException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@DisplayName("课程服务测试")
@ExtendWith(MockitoExtension.class)
public class CourseServiceImplTest {
    @Mock
    private CoursesRepository coursesRepository;
    @InjectMocks
    private CoursesServiceImpl coursesServiceImpl;
    @Test
    @DisplayName("当用户自己发布自己的课程时应该通过")
    void shouldThrowLearningSessionStatusException() {
        Courses courses = new Courses();
        courses.setId(1001L);
        courses.setUserId(1001L);
        courses.setPublisherId(1001L);
        courses.setCourseType(CoursesTypeEnum.PRIVATE);
        BaseContext.setCurrentId(1001L);
        when(coursesRepository.findCourseById(1001L)).thenReturn(courses);
        when(coursesRepository.publishCourse(courses)).thenReturn(1);
        assertDoesNotThrow(() -> coursesServiceImpl.publishCourse(1001L));
    }
    @Test
    @DisplayName("当用户自己发布自己的课程时但保存错误时")
    void shouldThrowLearningSessionStatusExceptionWhenServiceError() {
        Courses courses = new Courses();
        courses.setId(1001L);
        courses.setUserId(1001L);
        courses.setPublisherId(1001L);
        courses.setCourseType(CoursesTypeEnum.PRIVATE);
        BaseContext.setCurrentId(1001L);
        when(coursesRepository.findCourseById(1001L)).thenReturn(courses);
        when(coursesRepository.publishCourse(courses)).thenReturn(-1);
        assertThrows(CreateErrorException.class, () -> coursesServiceImpl.publishCourse(1001L));
    }
    @Test
    @DisplayName("当用户想发布不是自己的课程并且课程状态为PRIVATE时应该报错")
    void shouldThrowLearningSessionStatusExceptionWhenStatusPRIVATE() {
        Courses courses = new Courses();
        courses.setId(1001L);
        courses.setUserId(1002L);
        courses.setPublisherId(1002L);
        courses.setCourseType(CoursesTypeEnum.PRIVATE);
        BaseContext.setCurrentId(1001L);
        when(coursesRepository.findCourseById(1001L)).thenReturn(courses);
        assertThrows(ViolationOperationException.class, () -> coursesServiceImpl.publishCourse(1001L));
    }
    @Test
    @DisplayName("当用户想发布的课程并且课程状态为PUBLIC时应该报错")
    void shouldThrowLearningSessionStatusExceptionWhenStatusPUBLIC() {
        Courses courses = new Courses();
        courses.setId(1001L);
        courses.setUserId(1001L);
        courses.setPublisherId(1001L);
        courses.setCourseType(CoursesTypeEnum.PUBLIC);
        BaseContext.setCurrentId(1001L);
        when(coursesRepository.findCourseById(1001L)).thenReturn(courses);
        assertThrows(CreateStatusException.class, () -> coursesServiceImpl.publishCourse(1001L));
    }
}
