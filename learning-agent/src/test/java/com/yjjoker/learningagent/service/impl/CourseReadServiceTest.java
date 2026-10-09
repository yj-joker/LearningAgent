package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.exception.DataIllegalException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.CoursesVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// 仓库使用替身，验证自己的课程读取、登录边界及只读行为，不访问真实数据库。
class CourseReadServiceTest {
    private final CoursesRepository repository = mock(CoursesRepository.class);
    private final CoursesServiceImpl service = new CoursesServiceImpl(repository);

    // 清理登录上下文，避免后续用例继承此处的用户或管理员身份。
    @AfterEach
    void clearUser() {
        BaseContext.removeCurrentId();
        BaseContext.removeCurrentRole();
    }

    // 列表只用后端登录用户编号查询，保留课程正文、状态与时间，不执行任何写入。
    @Test
    void returnsCurrentUserCourseListWithoutWriting() {
        BaseContext.setCurrentId(7L);
        Courses first = course(11L, CoursesTypeEnum.PRIVATE);
        Courses second = course(12L, CoursesTypeEnum.PUBLISHED);
        when(repository.findCoursesByUserId(7L)).thenReturn(List.of(first, second));

        List<CoursesVO> result = service.findCurrentUserCourses();

        assertEquals(List.of(11L, 12L), result.stream().map(CoursesVO::getId).toList());
        assertEquals(first.getLearningOutline(), result.getFirst().getLearningOutline());
        assertEquals(first.getUpdatedAt(), result.getFirst().getUpdatedAt());
        verify(repository).findCoursesByUserId(7L);
        verify(repository, never()).createCourse(any());
        verify(repository, never()).updateCourseStatus(any(), any(), any(), any());
        verifyNoMoreInteractions(repository);
    }

    // 没有课程时正常返回空列表，不用本地缓存制造不存在的数据。
    @Test
    void returnsEmptyListWhenUserHasNoCourses() {
        BaseContext.setCurrentId(7L);
        when(repository.findCoursesByUserId(7L)).thenReturn(List.of());
        assertTrue(service.findCurrentUserCourses().isEmpty());
    }

    // 自己的私有、待审核与已发布课程都可读取，状态不会被读取接口修改。
    @ParameterizedTest
    @EnumSource(CoursesTypeEnum.class)
    void returnsOwnedCourseForEveryStatus(CoursesTypeEnum status) {
        BaseContext.setCurrentId(7L);
        Courses course = course(11L, status);
        when(repository.findCourseByIdAndUserId(11L, 7L)).thenReturn(course);

        CoursesVO result = service.findOwnedCourse(11L);

        assertEquals(11L, result.getId());
        assertEquals(status, result.getCourseType());
        assertEquals("课程 11", result.getCourseName());
        assertEquals(course.getCreatedAt(), result.getCreatedAt());
        verify(repository).findCourseByIdAndUserId(11L, 7L);
        verify(repository, never()).findCourseById(any());
        verifyNoMoreInteractions(repository);
    }

    // 不存在与别人的课程使用同一错误，管理员身份也不能通过此接口读取别人编辑详情。
    @Test
    void rejectsUnknownOrForeignCourseWithoutDisclosingExistence() {
        BaseContext.setCurrentId(7L);
        BaseContext.setCurrentRole(UserRoleEnum.ADMIN);
        when(repository.findCourseByIdAndUserId(11L, 7L)).thenReturn(null);
        NotFountException error = assertThrows(NotFountException.class, () -> service.findOwnedCourse(11L));
        assertEquals("课程不存在或无权查看", error.getMessage());
        verify(repository, never()).findCourseById(any());
    }

    // 未登录或无效用户编号在查询之前被拒绝，不能返回任何课程数据。
    @Test
    void requiresValidLoginBeforeReading() {
        assertThrows(ViolationOperationException.class, service::findCurrentUserCourses);
        assertThrows(ViolationOperationException.class, () -> service.findOwnedCourse(11L));
        BaseContext.setCurrentId(0L);
        assertThrows(ViolationOperationException.class, service::findCurrentUserCourses);
        verifyNoInteractions(repository);
    }

    // 非 HTTP 调用也校验课程编号，零、负数或空编号不能进入查询。
    @Test
    void rejectsInvalidCourseIdBeforeQuery() {
        BaseContext.setCurrentId(7L);
        assertThrows(DataIllegalException.class, () -> service.findOwnedCourse(null));
        assertThrows(DataIllegalException.class, () -> service.findOwnedCourse(0L));
        assertThrows(DataIllegalException.class, () -> service.findOwnedCourse(-1L));
        verifyNoInteractions(repository);
    }

    // 最小课程替身保留可显示信息，便于检查 VO 映射而不打印业务正文。
    private Courses course(Long id, CoursesTypeEnum status) {
        Courses course = new Courses();
        course.setId(id);
        course.setUserId(7L);
        course.setPublisherId(7L);
        course.setCourseName("课程 " + id);
        course.setDifficultyLevel(3L);
        course.setCourseType(status);
        course.setLearningOutline("{\"content\":\"课程大纲\"}");
        course.setCreatedAt(LocalDateTime.of(2026, 10, 8, 10, 0));
        course.setUpdatedAt(LocalDateTime.of(2026, 10, 8, 11, 0));
        return course;
    }
}
