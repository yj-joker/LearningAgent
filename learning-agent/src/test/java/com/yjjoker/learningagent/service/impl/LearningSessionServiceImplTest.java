package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.exception.CourseNotFountException;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.test.impl.CoursesRepositoryTestImpl;
import com.yjjoker.learningagent.repository.test.impl.LearningSessionRepositoryTestImpl;
import com.yjjoker.learningagent.service.LearningSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("学习会话服务测试")
class LearningSessionServiceImplTest {

    private LearningSessionService learningSessionService;

    @BeforeEach
    void setUp() {
        CoursesRepository coursesRepository = new CoursesRepositoryTestImpl(
                List.of(1001L, 1002L, 1003L),
                List.of(1001L, 1002L, 1003L),
                List.of(CoursesTypeEnum.PRIVATE, CoursesTypeEnum.PUBLIC, CoursesTypeEnum.PUBLIC)
        );
        learningSessionService = new LearningSessionServiceImpl(
                new LearningSessionRepositoryTestImpl(),
                coursesRepository
        );
    }

    @Nested
    @DisplayName("创建学习会话")
    class CreateSessionTests {

        @Test
        @DisplayName("课程不存在时，应抛出课程不存在异常")
        void shouldThrowCourseNotFoundExceptionWhenCourseDoesNotExist() {
            LearningSessionDTO request = createRequest(1004L, 1001L);

            CourseNotFountException exception = assertThrows(
                    CourseNotFountException.class,
                    () -> learningSessionService.createSession(request)
            );

            assertEquals("课程不存在", exception.getMessage());
        }

        @Test
        @DisplayName("用户无权访问私有课程时，应抛出非法创建异常")
        void shouldThrowCreateErrorExceptionWhenPrivateCourseIsNotOwnedByUser() {
            LearningSessionDTO request = createRequest(1001L, 1002L);

            CreateErrorException exception = assertThrows(
                    CreateErrorException.class,
                    () -> learningSessionService.createSession(request)
            );

            assertEquals("非法创建", exception.getMessage());
        }

        @Test
        @DisplayName("用户访问自己的私有课程时，应成功创建学习会话")
        void shouldCreateSessionWhenPrivateCourseIsOwnedByUser() {
            LearningSessionDTO request = createRequest(1001L, 1001L);

            assertDoesNotThrow(() -> learningSessionService.createSession(request));
        }
    }

    private LearningSessionDTO createRequest(Long courseId, Long userId) {
        LearningSessionDTO request = new LearningSessionDTO();
        request.setCourseId(courseId);
        request.setUserId(userId);
        return request;
    }
}
