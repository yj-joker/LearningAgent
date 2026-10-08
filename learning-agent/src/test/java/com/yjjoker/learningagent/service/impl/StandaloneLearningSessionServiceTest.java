package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.StandaloneLearningSessionDTO;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// 数据库与课程仓库使用替身，只验证会话范围、保存失败和模式历史选择。
class StandaloneLearningSessionServiceTest {
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final CoursesRepository courses = mock(CoursesRepository.class);
    private final LearningSessionMessageRepository messages = mock(LearningSessionMessageRepository.class);
    private final LearningSessionServiceImpl service = new LearningSessionServiceImpl(sessions, courses, messages);

    // 每个用例独立清理登录身份，防止影响后续测试。
    @AfterEach
    void clearUser() {
        BaseContext.removeCurrentId();
    }

    // 两种独立模式都不需要课程，且数据库编号和状态、时间会正确返回前端。
    @ParameterizedTest
    @EnumSource(value = AgentMode.class, names = {"CHAT", "FOCUS"})
    void createsStandaloneSessionWithoutCourse(AgentMode mode) {
        BaseContext.setCurrentId(7L);
        when(sessions.createSession(any())).thenAnswer(invocation -> {
            LearningSession session = invocation.getArgument(0);
            session.setId(12L);
            return 1;
        });
        LearningSessionVO result = service.createStandaloneSession(request("  理解线程  ", mode));

        ArgumentCaptor<LearningSession> saved = ArgumentCaptor.forClass(LearningSession.class);
        verify(sessions).createSession(saved.capture());
        assertNull(saved.getValue().getCourseId());
        assertEquals(7L, saved.getValue().getUserId());
        assertEquals(mode, saved.getValue().getMode());
        assertEquals(12L, result.getId());
        assertEquals("理解线程", result.getSessionTitle());
        assertEquals(LearningSessionStatusEnum.ACTIVE, result.getSessionStatus());
        assertEquals(mode, result.getMode());
        assertNotNull(result.getCreatedAt());
        assertEquals(result.getCreatedAt(), result.getUpdatedAt());
        assertEquals(result.getCreatedAt(), result.getCreateAt());
        assertEquals(result.getUpdatedAt(), result.getUpdateAt());
        verifyNoInteractions(courses, messages);
    }

    // 未登录用户既不能查看别人列表，也不能创建没有归属的会话。
    @Test
    void rejectsMissingUserBeforeAccessingDatabase() {
        assertThrows(LearningSessionStatusException.class, service::findSessions);
        assertThrows(LearningSessionStatusException.class,
                () -> service.createStandaloneSession(request("问题", AgentMode.CHAT)));
        verifyNoInteractions(sessions, courses, messages);
    }

    // 独立入口不能创建课程模式，空标题和过长标题也应在保存前拒绝。
    @Test
    void rejectsInvalidStandaloneInputs() {
        BaseContext.setCurrentId(7L);
        assertThrows(ClientDataErrorException.class,
                () -> service.createStandaloneSession(request("问题", AgentMode.COURSE)));
        assertThrows(ClientDataErrorException.class,
                () -> service.createStandaloneSession(request("问题", null)));
        assertThrows(ClientDataErrorException.class,
                () -> service.createStandaloneSession(request("   ", AgentMode.CHAT)));
        assertThrows(ClientDataErrorException.class,
                () -> service.createStandaloneSession(request("长".repeat(256), AgentMode.FOCUS)));
        verifyNoInteractions(sessions, courses, messages);
    }

    // 写入失败不能作为成功会话返回，避免前端使用不存在的会话编号继续聊天。
    @Test
    void failsWhenStandaloneInsertDoesNotSaveRow() {
        BaseContext.setCurrentId(7L);
        when(sessions.createSession(any())).thenReturn(0);
        assertThrows(LearningAgentServiceException.class,
                () -> service.createStandaloneSession(request("问题", AgentMode.CHAT)));
    }

    // 服务只把当前用户编号交给查询，列表包括课程、独立与已完成会话。
    @Test
    void loadsDatabaseListForCurrentUser() {
        BaseContext.setCurrentId(7L);
        LearningSessionVO course = new LearningSessionVO();
        course.setCourseId(3L);
        course.setMode(AgentMode.COURSE);
        course.setSessionStatus(LearningSessionStatusEnum.COMPLETED);
        LearningSessionVO standalone = new LearningSessionVO();
        standalone.setMode(AgentMode.FOCUS);
        standalone.setSessionStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findVisibleSessionsByUserId(7L)).thenReturn(List.of(course, standalone));

        assertEquals(List.of(course, standalone), service.findSessions());
        verify(sessions).findVisibleSessionsByUserId(7L);
        verifyNoInteractions(courses, messages);
    }

    // 指定 COURSE 时只加载课程历史；无模式参数保留原有全部展示消息的行为。
    @Test
    void filtersHistoryByModeAndKeepsLegacyCallCompatible() {
        BaseContext.setCurrentId(7L);
        LearningSession session = ownedSession(LearningSessionStatusEnum.ACTIVE);
        LearningSessionMessage message = new LearningSessionMessage();
        message.setAgentMode("COURSE");
        when(sessions.findSessionById(12L)).thenReturn(Optional.of(session));
        when(messages.findDisplayMessagesBySessionIdAndMode(12L, "COURSE")).thenReturn(List.of(message));
        when(messages.findDisplayMessagesBySessionId(12L)).thenReturn(List.of(message));

        assertEquals(List.of(message), service.findSessionMessages(12L, AgentMode.COURSE));
        assertEquals(List.of(message), service.findSessionMessages(12L));
        verify(messages).findDisplayMessagesBySessionIdAndMode(12L, "COURSE");
        verify(messages).findDisplayMessagesBySessionId(12L);
    }

    // 假删除会话即使知道编号也不能再读历史，过滤模式不能绕过归属校验。
    @Test
    void rejectsDeletedOrForeignSessionHistory() {
        BaseContext.setCurrentId(7L);
        LearningSession session = ownedSession(LearningSessionStatusEnum.CANCELED);
        when(sessions.findSessionById(12L)).thenReturn(Optional.of(session));
        assertThrows(LearningSessionStatusException.class,
                () -> service.findSessionMessages(12L, AgentMode.CHAT));
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        session.setUserId(9L);
        assertThrows(LearningSessionStatusException.class,
                () -> service.findSessionMessages(12L, AgentMode.FOCUS));
        verifyNoInteractions(messages);
    }

    // 生成最小独立请求，让每个用例只改变自己要检查的输入。
    private StandaloneLearningSessionDTO request(String title, AgentMode mode) {
        StandaloneLearningSessionDTO request = new StandaloneLearningSessionDTO();
        request.setSessionTitle(title);
        request.setMode(mode);
        return request;
    }

    // 创建已知归属的会话替身，不需要真实数据库或课程数据。
    private LearningSession ownedSession(LearningSessionStatusEnum status) {
        LearningSession session = new LearningSession();
        session.setId(12L);
        session.setUserId(7L);
        session.setStatus(status);
        return session;
    }
}
