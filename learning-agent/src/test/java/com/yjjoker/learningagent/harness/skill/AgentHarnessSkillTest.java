package com.yjjoker.learningagent.harness.skill;

import com.yjjoker.learningagent.config.HarnessSkillProperties;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.harness.AgentHarnessTestFactory;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.context.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.memory.service.ConversationMemoryService;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.harness.memory.service.MemoryReferenceRegistry;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import com.yjjoker.learningagent.harness.service.AgentHarnessService;
import com.yjjoker.learningagent.harness.skill.model.SkillDefinition;
import com.yjjoker.learningagent.harness.skill.model.SkillIndex;
import com.yjjoker.learningagent.harness.skill.service.SkillFileLoader;
import com.yjjoker.learningagent.harness.skill.service.SkillRegistry;
import com.yjjoker.learningagent.harness.skill.service.SkillRunContext;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.harness.tool.impl.LoadSkillTool;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 验证 Skill 进入主 AgentLoop 后的请求顺序、历史边界和独占批次规则。
class AgentHarnessSkillTest {
    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 9L;
    private static final String SKILL_NAME = "learning-plan-draft";
    private static final String SKILL_VERSION = "1.0.0";
    private static final String SKILL_BODY = "DETAILED_SKILL_BODY_ONLY_AFTER_LOAD";

    @AfterEach
    void clearUserContext() {
        // Harness 依赖登录上下文，测试结束后释放当前线程的用户身份。
        BaseContext.removeCurrentId();
    }

    @Test
    void loadsBodyOnlyInTheRequestAfterLoadAndDoesNotPersistItAsHistory() {
        BaseContext.setCurrentId(USER_ID);
        RecordingLlmClient client = new RecordingLlmClient(
                new ToolCallLlmResponse(List.of(new ToolCall("skill-call", "load_skill",
                        "{\"name\":\"learning-plan-draft\",\"version\":\"1.0.0\"}"))),
                new TextLlmResponse("已读取学习流程并回答。"));
        List<LlmMessage> savedHistory = new ArrayList<>();
        ConversationMemoryService history = history(savedHistory);
        SkillRunContext skills = skillContext();

        AgentHarnessService harness = harness(client, history, skills,
                new ToolRegistry(List.of(new LoadSkillTool(skills))));

        assertEquals("已读取学习流程并回答。", harness.run(SESSION_ID, "请按学习流程回答").getAnswer());
        assertFalse(client.requests.get(0).stream().anyMatch(message -> hasContent(message, SKILL_BODY)));
        assertTrue(client.requests.get(1).stream().anyMatch(message -> hasContent(message, SKILL_BODY)));
        assertEquals(1, client.requests.get(1).stream()
                .filter(message -> hasContent(message, SKILL_BODY)).count());
        assertTrue(savedHistory.stream().anyMatch(message -> "assistant".equals(message.getRole())
                && !message.isContextReplayable()));
        assertTrue(savedHistory.stream().anyMatch(message -> "tool".equals(message.getRole())
                && !message.isContextReplayable()));
        assertFalse(savedHistory.stream().anyMatch(message -> hasContent(message, SKILL_BODY)));
    }

    @Test
    void loadedBodyIsClearedBeforeTheNextUserRequest() {
        BaseContext.setCurrentId(USER_ID);
        RecordingLlmClient client = new RecordingLlmClient(
                new ToolCallLlmResponse(List.of(new ToolCall("skill-call", "load_skill",
                        "{\"name\":\"learning-plan-draft\",\"version\":\"1.0.0\"}"))),
                new TextLlmResponse("第一轮完成。"),
                new TextLlmResponse("第二轮完成。"));
        SkillRunContext skills = skillContext();
        AgentHarnessService harness = harness(client, history(new ArrayList<>()), skills,
                new ToolRegistry(List.of(new LoadSkillTool(skills))));

        harness.run(SESSION_ID, "读取学习流程");
        harness.run(SESSION_ID, "普通追问");

        // 新的逻辑任务只保留索引，不能继承上一轮的完整技能正文。
        List<LlmMessage> secondRunRequest = client.requests.getLast();
        assertFalse(secondRunRequest.stream().anyMatch(message -> hasContent(message, SKILL_BODY)));
        assertTrue(secondRunRequest.getFirst().getContent().contains(SKILL_NAME));
    }

    @Test
    void loadSkillCannotExecuteTogetherWithAnotherTool() {
        BaseContext.setCurrentId(USER_ID);
        AtomicInteger businessExecutions = new AtomicInteger();
        RecordingLlmClient client = new RecordingLlmClient(
                new ToolCallLlmResponse(List.of(
                        new ToolCall("skill-call", "load_skill",
                                "{\"name\":\"learning-plan-draft\",\"version\":\"1.0.0\"}"),
                        new ToolCall("business-call", "write_business", "{}"))),
                new TextLlmResponse("本批工具未执行。"));
        SkillRunContext skills = skillContext();
        Tool businessTool = new CountingTool(businessExecutions);
        AgentHarnessService harness = harness(client, history(new ArrayList<>()), skills,
                new ToolRegistry(List.of(new LoadSkillTool(skills), businessTool)));

        harness.run(SESSION_ID, "读取流程并写入业务数据");

        assertEquals(0, businessExecutions.get());
        assertFalse(client.requests.get(1).stream().anyMatch(message -> hasContent(message, SKILL_BODY)));
        assertTrue(client.requests.get(1).stream().anyMatch(message -> hasContent(message, "EXCLUSIVE_TOOL_BATCH")));
    }

    // 使用真实注册表和运行上下文，只有文件读取替身，验证主循环调用的是同一份状态。
    private SkillRunContext skillContext() {
        SkillFileLoader loader = mock(SkillFileLoader.class);
        when(loader.loadBuiltInSkills()).thenReturn(List.of(
                new SkillDefinition(new SkillIndex(SKILL_NAME, "学习流程草案", SKILL_VERSION), SKILL_BODY)));
        HarnessSkillProperties properties = new HarnessSkillProperties();
        return new SkillRunContext(new SkillRegistry(loader), properties);
    }

    // 组装测试所需的生产 Harness 构造链，不调用真实模型和数据库。
    private AgentHarnessService harness(RecordingLlmClient client,
                                        ConversationMemoryService history,
                                        SkillRunContext skills,
                                        ToolRegistry tools) {
        StructuredMemoryService memories = mock(StructuredMemoryService.class);
        when(memories.loadUserMemoryIndex(anyLong())).thenReturn(List.of());
        when(memories.loadSessionMemoryIndex(anyLong())).thenReturn(List.of());
        LearningSessionRepository sessions = mock(LearningSessionRepository.class);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        return AgentHarnessTestFactory.create(client, tools, List.of(), history, sessions,
                new ContextManager(40_000, 8_000), new InMemoryOriginalToolResultStoreImpl(),
                memories, new MemoryReferenceRegistry(), skills);
    }

    // 记录每次请求的不可变副本，避免下一轮追加消息改变前一次断言对象。
    private ConversationMemoryService history(List<LlmMessage> saved) {
        ConversationMemoryService history = mock(ConversationMemoryService.class);
        when(history.loadHistory(SESSION_ID, AgentMode.CHAT)).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.addAll(invocation.getArgument(1));
            return null;
        }).when(history).appendMessages(org.mockito.ArgumentMatchers.eq(SESSION_ID),
                org.mockito.ArgumentMatchers.eq(AgentMode.CHAT), org.mockito.ArgumentMatchers.anyList());
        return history;
    }

    // 工具请求消息没有正文，断言前先排除空内容。
    private boolean hasContent(LlmMessage message, String expected) {
        return message.getContent() != null && message.getContent().contains(expected);
    }

    // 固定返回顺序，模拟“先请求技能，再收到正文，最后给出回答”。
    private static final class RecordingLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<List<LlmMessage>> requests = new ArrayList<>();

        private RecordingLlmClient(LlmResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            requests.add(List.copyOf(messages));
            return responses.removeFirst();
        }
    }

    // 统计业务工具执行次数，验证独占批次在执行前就拒绝整批调用。
    private static final class CountingTool implements Tool {
        private final AtomicInteger executions;

        private CountingTool(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public String name() {
            return "write_business";
        }

        @Override
        public String description() {
            return "测试业务写入";
        }

        @Override
        public ToolExecutionResult execute(String input) {
            executions.incrementAndGet();
            return ToolExecutionResult.success("业务已执行");
        }
    }
}
