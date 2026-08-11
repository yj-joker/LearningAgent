package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.exception.CourseStatusException;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.CoursesVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("课程服务状态流转测试")
@ExtendWith(MockitoExtension.class)
class CourseServiceImplTest {
    private static final long COURSE_ID = 1001L;
    private static final long OWNER_ID = 2001L;
    private static final long ADMIN_ID = 3001L;

    @Mock
    private CoursesRepository coursesRepository;

    @InjectMocks
    private CoursesServiceImpl coursesService;

    @AfterEach
    void clearBaseContext() {
        BaseContext.removeCurrentId();
        BaseContext.removeCurrentRole();
    }

    @Nested
    @DisplayName("合法状态流转")
    class ValidTransitionTests {

        @Test
        @DisplayName("课程拥有者可以将 PRIVATE 课程提交为 PENDING")
        void shouldPublishPrivateCourseToPending() {
            Courses course = courseWithStatus(CoursesTypeEnum.PRIVATE);
            signInAsOwner();
            when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);
            when(coursesRepository.updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PRIVATE),
                    eq(CoursesTypeEnum.PENDING), any(LocalDateTime.class))).thenReturn(1);

            CoursesVO result = coursesService.publishCourse(COURSE_ID);

            assertEquals(CoursesTypeEnum.PENDING, course.getCourseType());
            assertEquals(CoursesTypeEnum.PENDING, result.getCourseType());
            assertEquals(COURSE_ID, result.getId());
            verify(coursesRepository).updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PRIVATE),
                    eq(CoursesTypeEnum.PENDING), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("管理员可以将 PENDING 课程审核为 PUBLISHED")
        void shouldPassPendingCourseToPublished() {
            Courses course = courseWithStatus(CoursesTypeEnum.PENDING);
            signInAsAdmin();
            when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);
            when(coursesRepository.updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PENDING),
                    eq(CoursesTypeEnum.PUBLISHED), any(LocalDateTime.class))).thenReturn(1);

            CoursesVO result = coursesService.passCourse(COURSE_ID);

            assertEquals(CoursesTypeEnum.PUBLISHED, course.getCourseType());
            assertEquals(CoursesTypeEnum.PUBLISHED, result.getCourseType());
            verify(coursesRepository).updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PENDING),
                    eq(CoursesTypeEnum.PUBLISHED), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("管理员可以将 PENDING 课程驳回为 PRIVATE")
        void shouldRejectPendingCourseToPrivate() {
            Courses course = courseWithStatus(CoursesTypeEnum.PENDING);
            signInAsAdmin();
            when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);
            when(coursesRepository.updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PENDING),
                    eq(CoursesTypeEnum.PRIVATE), any(LocalDateTime.class))).thenReturn(1);

            CoursesVO result = coursesService.rejectCourse(COURSE_ID);

            assertEquals(CoursesTypeEnum.PRIVATE, course.getCourseType());
            assertEquals(CoursesTypeEnum.PRIVATE, result.getCourseType());
            verify(coursesRepository).updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PENDING),
                    eq(CoursesTypeEnum.PRIVATE), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("管理员可以将 PUBLISHED 课程下架为 PRIVATE")
        void shouldUnpublishPublishedCourseToPrivate() {
            Courses course = courseWithStatus(CoursesTypeEnum.PUBLISHED);
            signInAsAdmin();
            when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);
            when(coursesRepository.updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PUBLISHED),
                    eq(CoursesTypeEnum.PRIVATE), any(LocalDateTime.class))).thenReturn(1);

            CoursesVO result = coursesService.rejectCourse(COURSE_ID);

            assertEquals(CoursesTypeEnum.PRIVATE, course.getCourseType());
            assertEquals(CoursesTypeEnum.PRIVATE, result.getCourseType());
            verify(coursesRepository).updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PUBLISHED),
                    eq(CoursesTypeEnum.PRIVATE), any(LocalDateTime.class));
        }
    }

    @Nested
    @DisplayName("非法状态与权限")
    class InvalidTransitionTests {

        @Test
        @DisplayName("非课程拥有者不能提交课程")
        void shouldRejectPublishByNonOwner() {
            Courses course = courseWithStatus(CoursesTypeEnum.PRIVATE);
            BaseContext.setCurrentId(9999L);
            when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);

            assertThrows(ViolationOperationException.class, () -> coursesService.publishCourse(COURSE_ID));

            verify(coursesRepository, never()).updateCourseStatus(any(), any(), any(), any());
        }

        @Test
        @DisplayName("PENDING 以外的课程不能再次提交审核")
        void shouldRejectPublishWhenCourseIsNotPrivate() {
            Courses course = courseWithStatus(CoursesTypeEnum.PENDING);
            signInAsOwner();
            when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);

            assertThrows(CourseStatusException.class, () -> coursesService.publishCourse(COURSE_ID));

            verify(coursesRepository, never()).updateCourseStatus(any(), any(), any(), any());
        }

        @Test
        @DisplayName("非管理员不能审核课程")
        void shouldRejectPassByNonAdmin() {
            BaseContext.setCurrentId(OWNER_ID);
            BaseContext.setCurrentRole(UserRoleEnum.USER);

            assertThrows(ViolationOperationException.class, () -> coursesService.passCourse(COURSE_ID));

            verify(coursesRepository, never()).findCourseById(any());
            verify(coursesRepository, never()).updateCourseStatus(any(), any(), any(), any());
        }

        @Test
        @DisplayName("数据库旧状态已变化时，应拒绝本次过期更新")
        void shouldRejectWhenCourseStatusWasChangedConcurrently() {
            Courses course = courseWithStatus(CoursesTypeEnum.PRIVATE);
            signInAsOwner();
            when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);
            when(coursesRepository.updateCourseStatus(eq(COURSE_ID), eq(CoursesTypeEnum.PRIVATE),
                    eq(CoursesTypeEnum.PENDING), any(LocalDateTime.class))).thenReturn(0);

            assertThrows(CourseStatusException.class, () -> coursesService.publishCourse(COURSE_ID));
        }

        @Test
        @DisplayName("课程不存在时不应执行状态更新")
        void shouldThrowNotFoundWhenCourseDoesNotExist() {
            signInAsOwner();
            when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(null);

            assertThrows(NotFountException.class, () -> coursesService.publishCourse(COURSE_ID));

            verify(coursesRepository, never()).updateCourseStatus(any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("创建课程保存失败时应抛出异常，且状态必须为 PRIVATE")
    void shouldRejectCourseCreationWhenDatabaseInsertFails() {
        CoursesDTO coursesDTO = new CoursesDTO();
        coursesDTO.setCourseName("Java 并发编程");
        coursesDTO.setDifficultyLevel(3L);
        signInAsOwner();
        when(coursesRepository.createCourse(any(Courses.class))).thenReturn(0);

        assertThrows(CreateErrorException.class, () -> coursesService.createCourse(coursesDTO));

        ArgumentCaptor<Courses> captor = ArgumentCaptor.forClass(Courses.class);
        verify(coursesRepository).createCourse(captor.capture());
        assertEquals(CoursesTypeEnum.PRIVATE, captor.getValue().getCourseType());
        assertEquals(OWNER_ID, captor.getValue().getUserId());
        assertTrue(captor.getValue().getCreatedAt().isBefore(LocalDateTime.now().plusSeconds(1)));
        assertNotNull(captor.getValue().getUpdatedAt());
    }

    @Test
    @DisplayName("课程拥有者可以查看自己的私有课程")
    void shouldAllowOwnerToViewPrivateCourse() {
        Courses course = courseWithStatus(CoursesTypeEnum.PRIVATE);
        signInAsOwner();
        when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);

        assertDoesNotThrow(() -> coursesService.checkUserCanViewCourse(COURSE_ID));
    }

    @Test
    @DisplayName("非拥有者可以查看已发布课程")
    void shouldAllowNonOwnerToViewPublishedCourse() {
        Courses course = courseWithStatus(CoursesTypeEnum.PUBLISHED);
        BaseContext.setCurrentId(9999L);
        when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);

        assertDoesNotThrow(() -> coursesService.checkUserCanViewCourse(COURSE_ID));
    }

    @Test
    @DisplayName("非拥有者不能查看私有课程")
    void shouldRejectNonOwnerViewingPrivateCourse() {
        Courses course = courseWithStatus(CoursesTypeEnum.PRIVATE);
        BaseContext.setCurrentId(9999L);
        when(coursesRepository.findCourseById(COURSE_ID)).thenReturn(course);

        assertThrows(
                ViolationOperationException.class,
                () -> coursesService.checkUserCanViewCourse(COURSE_ID)
        );
    }

    private Courses courseWithStatus(CoursesTypeEnum courseStatus) {
        Courses course = new Courses();
        course.setId(COURSE_ID);
        course.setUserId(OWNER_ID);
        course.setPublisherId(OWNER_ID);
        course.setCourseName("Java 并发编程");
        course.setDifficultyLevel(3L);
        course.setCourseType(courseStatus);
        return course;
    }

    private void signInAsOwner() {
        BaseContext.setCurrentId(OWNER_ID);
        BaseContext.setCurrentRole(UserRoleEnum.USER);
    }

    private void signInAsAdmin() {
        BaseContext.setCurrentId(ADMIN_ID);
        BaseContext.setCurrentRole(UserRoleEnum.ADMIN);
    }
}
