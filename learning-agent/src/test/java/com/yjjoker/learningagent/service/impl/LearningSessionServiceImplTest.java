package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


@DisplayName("学习会话服务测试")
@ExtendWith(MockitoExtension.class)
class LearningSessionServiceImplTest {
    @Mock
    private CoursesRepository coursesRepository;
    @Mock
    private LearningSessionRepository learningSessionRepository;
    @Mock
    private LearningSessionMessageRepository learningSessionMessageRepository;
    @InjectMocks
    private LearningSessionServiceImpl learningSessionServiceImpl;

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Nested
    @DisplayName("创建学习会话")
    class CreateSessionTests {

        @Test
        @DisplayName("课程不存在时，应抛出课程不存在异常")
        void shouldThrowCourseNotFoundExceptionWhenCourseDoesNotExist() {
            when(coursesRepository.findCourseById(1111L)).thenReturn(null);
            LearningSessionDTO learningSessionDTO = new LearningSessionDTO();
            learningSessionDTO.setCourseId(1111L);
            assertThrows(NotFountException.class, () ->
                    learningSessionServiceImpl.createSession(learningSessionDTO));
            verify(coursesRepository, times(1)).findCourseById(1111L);
            verify(learningSessionRepository, never()).createSession(any());
        }

        @Test
        @DisplayName("用户无权访问私有课程时，应抛出非法创建异常")
        void shouldThrowCreateErrorExceptionWhenPrivateCourseIsNotOwnedByUser() {
            Courses courses = new Courses();
            courses.setCourseType(CoursesTypeEnum.PRIVATE);
            courses.setUserId(2222L);
            when(coursesRepository.findCourseById(2222L)).thenReturn(courses);
            LearningSessionDTO learningSessionDTO = new LearningSessionDTO();
            learningSessionDTO.setCourseId(2222L);
            BaseContext.setCurrentId(3333L);
            assertThrows(CreateErrorException.class, () ->
                    learningSessionServiceImpl.createSession(learningSessionDTO));
            verify(learningSessionRepository, never()).createSession(any());
        }

        @Test
        @DisplayName("用户访问自己的私有课程时，应成功创建学习会话")
        void shouldCreateSessionWhenPrivateCourseIsOwnedByUser() {
            Courses courses = new Courses();
            courses.setCourseType(CoursesTypeEnum.PRIVATE);
            courses.setUserId(2222L);
            courses.setPublisherId(2222L);
            when(learningSessionRepository.createSession(any())).thenReturn(1);
            when(coursesRepository.findCourseById(2222L)).thenReturn(courses);
            LearningSessionDTO learningSessionDTO = new LearningSessionDTO();
            learningSessionDTO.setCourseId(2222L);
            BaseContext.setCurrentId(2222L);
            learningSessionDTO.setSessionTitle("Java学习会话");

            LearningSessionVO result = assertDoesNotThrow(() ->
                    learningSessionServiceImpl.createSession(learningSessionDTO));

            ArgumentCaptor<LearningSession> captor = ArgumentCaptor.forClass(LearningSession.class);
            verify(learningSessionRepository, times(1)).createSession(captor.capture());
            assertEquals("Java学习会话", captor.getValue().getSessionTitle());
            assertEquals(LearningSessionStatusEnum.ACTIVE, captor.getValue().getStatus());
            assertEquals("Java学习会话", result.getSessionTitle());
            assertEquals(LearningSessionStatusEnum.ACTIVE, result.getSessionStatus());
        }

        @Test
        @DisplayName("保存学习会话失败时，应抛出服务异常")
        void shouldThrowServiceExceptionWhenSessionCannotBeSaved() {
            Courses courses = new Courses();
            courses.setCourseType(CoursesTypeEnum.PUBLISHED);
            when(coursesRepository.findCourseById(2222L)).thenReturn(courses);
            when(learningSessionRepository.createSession(any())).thenReturn(0);
            LearningSessionDTO learningSessionDTO = new LearningSessionDTO();
            learningSessionDTO.setCourseId(2222L);
            BaseContext.setCurrentId(2222L);
            learningSessionDTO.setSessionTitle("保存失败的学习会话");

            assertThrows(LearningAgentServiceException.class, () ->
                    learningSessionServiceImpl.createSession(learningSessionDTO));
        }
    }

    @Test
    @DisplayName("用户访问已发布课程时，应成功创建学习会话")
    void shouldCreateSessionWhenPublishedCourseIsAccessible() {
        Courses courses = new Courses();
        courses.setCourseType(CoursesTypeEnum.PUBLISHED);
        courses.setUserId(3333L);
        courses.setPublisherId(3333L);
        when(learningSessionRepository.createSession(any())).thenReturn(1);
        when(coursesRepository.findCourseById(2222L)).thenReturn(courses);
        LearningSessionDTO learningSessionDTO = new LearningSessionDTO();
        learningSessionDTO.setCourseId(2222L);
        BaseContext.setCurrentId(2222L);
        learningSessionDTO.setSessionTitle("已发布课程学习会话");
        assertDoesNotThrow(() -> learningSessionServiceImpl.createSession(learningSessionDTO));
        verify(learningSessionRepository, times(1)).createSession(any());
    }

    @Test
    @DisplayName("完成学习会话，会话状态正确流转")
    void shouldTransitionSessionStatusCorrectly() {
        LearningSession learningSession = new LearningSession();
        learningSession.setId(2222L);
        learningSession.setUserId(2222L);
        learningSession.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(learningSessionRepository.findSessionById(2222L)).
                thenReturn(Optional.of(learningSession));
        when(learningSessionRepository.updateSession(any())).thenReturn(1);
        BaseContext.setCurrentId(2222L);
        assertDoesNotThrow(() -> learningSessionServiceImpl.changeSessionStatus(2222L));
        verify(learningSessionRepository, times(1)).updateSession(any());

    }

    @Test
    @DisplayName("非法完成学习会话，已完成的会话不能再次完成")
    void shouldThrowLearningSessionStatusExceptionWhenInvalid() {
        LearningSession learningSession = new LearningSession();
        learningSession.setId(2222L);
        learningSession.setUserId(2222L);
        learningSession.setStatus(LearningSessionStatusEnum.COMPLETED);
        when(learningSessionRepository.findSessionById(2222L)).
                thenReturn(Optional.of(learningSession));
        BaseContext.setCurrentId(2222L);
        assertThrows(LearningSessionStatusException.class,
                () ->learningSessionServiceImpl.changeSessionStatus(2222L));
        verify(learningSessionRepository,never()).updateSession(any());
    }

    @Test
    @DisplayName("用户查看自己的会话时，应按顺序返回展示消息")
    void shouldReturnAllSessionMessagesForOwner() {
        LearningSession session = new LearningSession();
        session.setId(2222L);
        session.setUserId(2222L);
        session.setStatus(LearningSessionStatusEnum.COMPLETED);
        LearningSessionMessage first = new LearningSessionMessage();
        first.setId(1L);
        first.setSessionId(2222L);
        LearningSessionMessage second = new LearningSessionMessage();
        second.setId(2L);
        second.setSessionId(2222L);

        BaseContext.setCurrentId(2222L);
        when(learningSessionRepository.findSessionById(2222L)).thenReturn(Optional.of(session));
        when(learningSessionMessageRepository.findDisplayMessagesBySessionId(2222L))
                .thenReturn(List.of(first, second));

        List<LearningSessionMessage> result = learningSessionServiceImpl.findSessionMessages(2222L);

        assertEquals(List.of(first, second), result);
        verify(learningSessionMessageRepository).findDisplayMessagesBySessionId(2222L);
    }

    @Test
    @DisplayName("用户查看别人的会话时，应拒绝查询消息")
    void shouldRejectMessagesFromAnotherUser() {
        LearningSession session = new LearningSession();
        session.setId(2222L);
        session.setUserId(9999L);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);

        BaseContext.setCurrentId(2222L);
        when(learningSessionRepository.findSessionById(2222L)).thenReturn(Optional.of(session));

        assertThrows(LearningSessionStatusException.class,
                () -> learningSessionServiceImpl.findSessionMessages(2222L));
        verifyNoInteractions(learningSessionMessageRepository);
    }


}
