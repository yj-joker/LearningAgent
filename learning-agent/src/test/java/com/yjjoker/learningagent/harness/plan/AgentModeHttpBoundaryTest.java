package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.controller.AgentController;
import com.yjjoker.learningagent.harness.approval.AgentRunCheckpoint;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.harness.service.AgentHarnessService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 只验证 HTTP 字段映射；真实模型的 HTTP 验证单独执行。
class AgentModeHttpBoundaryTest {
    private final AgentHarnessService harness = mock(AgentHarnessService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(harness)).build();

    // 未传模式保留为空，服务必须读取会话模式，不能被 DTO 默认值误导。
    @Test
    void keepsMissingModeNullForSessionLookup() throws Exception {
        mvc.perform(post("/agent/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":9,\"userMessage\":\"学习\"}")).andExpect(status().isOk());
        verify(harness).run(9L, "学习", null);
    }

    // 显式模式从请求进入服务层，不丢弃新字段。
    @Test
    void forwardsFocusMode() throws Exception {
        mvc.perform(post("/agent/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":9,\"userMessage\":\"学习\",\"mode\":\"FOCUS\"}")).andExpect(status().isOk());
        verify(harness).run(9L, "学习", AgentMode.FOCUS);
    }

    // 拼错模式直接拒绝，不能静默降级后宣称进入专注模式。
    @Test
    void rejectsUnknownMode() throws Exception {
        mvc.perform(post("/agent/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":9,\"userMessage\":\"学习\",\"mode\":\"UNKNOWN\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(harness);
    }

    // 旧检查点未包含新字段时保持 CHAT，新检查点可以往返恢复 FOCUS。
    @Test
    void roundTripsCheckpointModeAndReadsLegacy() {
        JsonMapper json = new JsonMapper();
        assertEquals(AgentMode.CHAT, json.readValue("{}", AgentRunCheckpoint.class).getMode());
        AgentRunCheckpoint saved = new AgentRunCheckpoint();
        saved.setMode(AgentMode.FOCUS);
        assertEquals(AgentMode.FOCUS,
                json.readValue(json.writeValueAsString(saved), AgentRunCheckpoint.class).getMode());
    }
}
