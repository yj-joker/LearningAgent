package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.service.LearningSessionService;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 用真实 MVC 参数绑定与 DTO 校验验证接口，服务替身不连接真实数据库。
class LearningSessionControllerTest {
    private final LearningSessionService service = mock(LearningSessionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LearningSessionController(service)).build();

    // 全部会话路由返回列表与模式字段，前端不需要从本地存储拼装会话。
    @Test
    void returnsCurrentUserSessionList() throws Exception {
        LearningSessionVO session = new LearningSessionVO();
        session.setId(12L);
        session.setSessionTitle("理解线程");
        session.setMode(AgentMode.FOCUS);
        when(service.findSessions()).thenReturn(List.of(session));
        mvc.perform(get("/learning-agent/learning/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(12))
                .andExpect(jsonPath("$.data[0].mode").value("FOCUS"));
        verify(service).findSessions();
    }

    // 独立创建请求没有 courseId 仍合法，绑定的模式可以直接交给服务。
    @Test
    void acceptsStandaloneRequestWithoutCourse() throws Exception {
        when(service.createStandaloneSession(any())).thenReturn(new LearningSessionVO());
        mvc.perform(post("/learning-agent/learning/standalone-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionTitle\":\"理解线程\",\"mode\":\"CHAT\"}"))
                .andExpect(status().isOk());
        verify(service).createStandaloneSession(argThat(request ->
                request.getMode() == AgentMode.CHAT && "理解线程".equals(request.getSessionTitle())));
    }

    // 缺失模式、空标题和未知枚举先被 HTTP 边界拒绝，不能进入数据库保存流程。
    @Test
    void rejectsMalformedStandaloneRequest() throws Exception {
        for (String body : List.of("{\"sessionTitle\":\"问题\"}",
                "{\"sessionTitle\":\"  \",\"mode\":\"CHAT\"}",
                "{\"sessionTitle\":\"问题\",\"mode\":\"UNKNOWN\"}")) {
            mvc.perform(post("/learning-agent/learning/standalone-session")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    // 历史模式参数正确绑定，消息响应公开模式供页面识别而无需读取内部上下文。
    @Test
    void acceptsOptionalHistoryModeAndReturnsMessageMode() throws Exception {
        LearningSessionMessage message = new LearningSessionMessage();
        message.setId(14L);
        message.setAgentMode("COURSE");
        when(service.findSessionMessages(12L, AgentMode.COURSE)).thenReturn(List.of(message));
        when(service.findSessionMessages(12L, null)).thenReturn(List.of());
        mvc.perform(get("/learning-agent/learning/session/12/messages").param("mode", "COURSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].agentMode").value("COURSE"));
        mvc.perform(get("/learning-agent/learning/session/12/messages"))
                .andExpect(status().isOk());
        verify(service).findSessionMessages(12L, AgentMode.COURSE);
        verify(service).findSessionMessages(12L, null);
    }
}
