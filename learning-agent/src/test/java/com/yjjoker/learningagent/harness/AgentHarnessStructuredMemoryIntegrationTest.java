package com.yjjoker.learningagent.harness;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.hook.AgentHook;
import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.hook.ToolCallHookResult;
import com.yjjoker.learningagent.harness.hook.ToolExecutionRecordingHook;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.context.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.memory.impl.LlmMemoryExtractionService;
import com.yjjoker.learningagent.harness.memory.model.MemoryOperation;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.harness.memory.model.MemoryWriteReceipt;
import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationScheduler;
import com.yjjoker.learningagent.harness.service.AgentHarnessServiceImpl;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.memory.service.ConversationMemoryService;
import com.yjjoker.learningagent.harness.memory.service.MemoryReferenceRegistry;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import com.yjjoker.learningagent.harness.service.AgentHarnessService;
import com.yjjoker.learningagent.harness.tool.impl.RecallMemoryTool;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;

class AgentHarnessStructuredMemoryIntegrationTest {

    // 测试使用固定用户和会话，验证索引查询的作用域。
    private static final Long USER_ID = 20L;
    private static final Long SESSION_ID = 10L;

    @AfterEach
    void clearUserContext() {
        // ThreadLocal 必须清理，避免影响其他测试用例。
        BaseContext.removeCurrentId();
    }

    @Test
    void shouldSendMemoryIndexButNotMemoryContentToLlm() {
        // Harness 的权限校验依赖当前登录用户。
        BaseContext.setCurrentId(USER_ID);

        // 长期记忆同时准备摘要和正文，验证只发送摘要。
        UserMemory userMemory = new UserMemory();
        userMemory.setId(1L);
        userMemory.setUserId(USER_ID);
        userMemory.setMemoryKey("learning_language");
        userMemory.setMemoryTopic("learning_background");
        userMemory.setMemorySummary("用户正在学习 Java");
        userMemory.setMemoryContent("这是不应该直接发送给模型的长期记忆正文");

        // 会话记忆同样同时准备摘要和正文，验证不会越过索引阶段。
        SessionMemory sessionMemory = new SessionMemory();
        sessionMemory.setId(2L);
        sessionMemory.setSessionId(SESSION_ID);
        sessionMemory.setMemoryKey("current_goal");
        sessionMemory.setMemoryTopic("task_state");
        sessionMemory.setMemorySummary("当前正在接入结构化记忆");
        sessionMemory.setMemoryContent("这是不应该直接发送给模型的会话记忆正文");

        // Mock 记忆服务，只返回本测试需要的两条索引。
        StructuredMemoryService structuredMemoryService = mock(StructuredMemoryService.class);
        when(structuredMemoryService.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(userMemory));
        when(structuredMemoryService.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of(sessionMemory));

        // 记录模型收到的消息，检查 system prompt 的实际内容。
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        when(conversationMemoryService.loadHistory(SESSION_ID)).thenReturn(List.of());

        // Mock 会话仓库，让 Harness 通过访问权限校验。
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessionRepository.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));

        // 直接使用带 StructuredMemoryService 的生产构造器，验证真实接入路径。
        AgentHarnessService harness = AgentHarnessTestFactory.create(
                llmClient,
                new ToolRegistry(List.of()),
                List.of(),
                conversationMemoryService,
                sessionRepository,
                new ContextManager(40_000, 8_000),
                new InMemoryOriginalToolResultStoreImpl(),
                structuredMemoryService
        );

        harness.run(SESSION_ID, "继续当前学习任务");

        // 第一条消息是 system，记忆索引应该位于其中。
        String systemPrompt = llmClient.messages.getFirst().getFirst().getContent();
        // 读取真实索引引用，不假定每个任务都使用同一组 memory_1、memory_2。
        var visibleRefs = systemPrompt.lines().filter(line -> line.startsWith("- memoryRef="))
                .map(line -> line.substring("- memoryRef=".length()).split("，")[0]).toList();
        assertEquals(2, visibleRefs.size());
        assertTrue(visibleRefs.get(0).endsWith("_1"));
        assertTrue(visibleRefs.get(1).endsWith("_2"));
        assertFalse(systemPrompt.contains("memoryId=1"));
        assertFalse(systemPrompt.contains("memoryId=2"));
        assertTrue(systemPrompt.contains("learning_language"));
        assertTrue(systemPrompt.contains("当前正在接入结构化记忆"));
        assertFalse(systemPrompt.contains("这是不应该直接发送给模型的长期记忆正文"));
        assertFalse(systemPrompt.contains("这是不应该直接发送给模型的会话记忆正文"));
    }

    @Test
    void shouldUseMemoryRefToRecallContentInsideAgentLoop() {
        BaseContext.setCurrentId(USER_ID);

        // 索引中的摘要只说明主题，正文由召回工具返回。
        UserMemory memory = new UserMemory();
        memory.setId(998877L);
        memory.setUserId(USER_ID);
        memory.setMemoryKey("sports_preference");
        memory.setMemoryTopic("personal_preference");
        memory.setMemorySummary("用户的运动偏好");
        memory.setMemoryContent("用户喜欢篮球，并且每周打三次球");

        StructuredMemoryService memoryService = mock(StructuredMemoryService.class);
        when(memoryService.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(memory));
        when(memoryService.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of());
        when(memoryService.recallUserMemory(USER_ID, memory.getId())).thenReturn(memory);

        MemoryReferenceRegistry referenceRegistry = new MemoryReferenceRegistry();
        SequenceLlmClient llmClient = new SequenceLlmClient(
                new ToolCallLlmResponse(List.of(new ToolCall(
                        "call_memory", "recall_memory", "{\"memoryRef\":\"${CURRENT_MEMORY_REF}\"}"
                ))),
                new TextLlmResponse("你喜欢篮球，并且每周打三次球。")
        );

        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessionRepository.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));

        AgentHarnessService harness = AgentHarnessTestFactory.create(
                llmClient,
                new ToolRegistry(List.of(new RecallMemoryTool(referenceRegistry, memoryService))),
                List.of(),
                mock(ConversationMemoryService.class),
                sessionRepository,
                new ContextManager(40_000, 8_000),
                new InMemoryOriginalToolResultStoreImpl(),
                memoryService,
                referenceRegistry
        );

        String answer = harness.run(SESSION_ID, "我喜欢什么运动？").getAnswer();

        assertEquals("你喜欢篮球，并且每周打三次球。", answer);
        assertTrue(llmClient.messages.get(1).stream()
                .anyMatch(message -> "tool".equals(message.getRole())
                        && message.getContent().contains("用户喜欢篮球")));
    }

    // 主循环结束后使用最新索引提取，不能沿用回答开始时的旧映射。
    @Test
    void shouldExtractAndPersistWithFreshIndexAfterAnswer() {
        BaseContext.setCurrentId(USER_ID);
        UserMemory oldMemory = indexedMemory(1L, "oldSport");
        UserMemory currentMemory = indexedMemory(2L, "favoriteSport");
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        // 第一次加载供主模型回答，第二次加载供后置提取使用。
        when(store.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(oldMemory), List.of(currentMemory));
        when(store.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of());
        when(store.lockUserMemory(USER_ID, 2L)).thenReturn(currentMemory);
        LlmClient client = mock(LlmClient.class);
        when(client.generate(anyList())).thenReturn(new TextLlmResponse("了解你的新偏好。"));
        when(client.generateWithoutTools(anyList())).thenReturn(new TextLlmResponse("""
                {"memories":[{"scope":"USER","operation":"UPDATE",
                "targetMemoryRefs":["memory_1"],"userEvidence":"现在最喜欢足球",
                "memoryTopic":"运动偏好","memorySummary":"最喜欢足球","memoryContent":"现在最喜欢足球"}]}
                """));
        ConversationMemoryService history = mock(ConversationMemoryService.class);
        MemoryConsolidationScheduler consolidation = mock(MemoryConsolidationScheduler.class);

        String answer = extractionHarness(client, store, history, consolidation).run(SESSION_ID, "现在最喜欢足球").getAnswer();

        assertEquals("了解你的新偏好。", answer);
        // 自动提取现在只创建审批申请，用户批准前不能改变数据库对象。
        assertEquals("最喜欢羽毛球", currentMemory.getMemoryContent());
        assertEquals("favoriteSport", currentMemory.getMemoryKey());
        verify(store, never()).updateUserMemory(currentMemory);
        verify(store, never()).lockUserMemory(USER_ID, 1L);
        // 先生成回答、再提取候选，正常结束后由 Hook 通知整理调度器。
        var order = org.mockito.Mockito.inOrder(client, store, consolidation);
        order.verify(client).generate(anyList());
        order.verify(client).generateWithoutTools(anyList());
        order.verify(consolidation).request(USER_ID, SESSION_ID);
    }

    // 引用修复失败时仍返回主模型的回答，不允许错误候选进入数据库。
    @Test
    void shouldKeepAnswerWhenExtractionReferencesRemainInvalid() {
        BaseContext.setCurrentId(USER_ID);
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        when(store.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(indexedMemory(1L, "sport")));
        when(store.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of());
        LlmClient client = mock(LlmClient.class);
        when(client.generate(anyList())).thenReturn(new TextLlmResponse("这是正常回答。"));
        when(client.generateWithoutTools(anyList())).thenReturn(new TextLlmResponse("""
                {"memories":[{"scope":"USER","operation":"DELETE",
                "targetMemoryRefs":["memory_999"],"userEvidence":"忘记运动"}]}
                """));

        String answer = extractionHarness(client, store, mock(ConversationMemoryService.class))
                .run(SESSION_ID, "忘记运动").getAnswer();

        assertEquals("这是正常回答。", answer);
        verify(client, org.mockito.Mockito.times(2)).generateWithoutTools(anyList());
        verify(store, never()).updateUserMemory(any());
        verify(store, never()).deleteUserMemory(any(), any());
    }

    // 实际召回仍由后置 Hook 记录，但不会进入提取模型的工具列表。
    @Test
    void shouldPassRecordedRecallToExtractionBeforeAfterRun() {
        BaseContext.setCurrentId(USER_ID);
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        UserMemory memory = indexedMemory(1L, "sport");
        when(store.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(memory));
        when(store.recallUserMemory(USER_ID, 1L)).thenReturn(memory);
        MemoryReferenceRegistry references = new MemoryReferenceRegistry();
        LlmClient client = toolThenAnswerClient("recall_memory", "{\"memoryRef\":\"${CURRENT_MEMORY_REF}\"}");
        ToolExecutionRecordingHook recorder = org.mockito.Mockito.spy(new ToolExecutionRecordingHook());
        AgentHarnessService harness = extractionHarness(client, store, mock(ConversationMemoryService.class),
                mock(MemoryConsolidationScheduler.class),
                List.of(new RecallMemoryTool(references, store)), List.of(recorder), references);

        assertEquals("正常回答", harness.run(SESSION_ID, "查询我的旧记忆").getAnswer());

        var capture = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).generateWithoutTools(capture.capture());
        List<LlmMessage> messages = capture.getValue();
        assertTrue(new tools.jackson.databind.json.JsonMapper().readTree(messages.get(1).getContent())
                .get("toolExecutions").isEmpty());
        var finished = org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
        verify(recorder).afterRun(finished.capture());
        assertEquals(1, finished.getValue().getToolExecutions().size());
        assertTrue(finished.getValue().getToolExecutions().getFirst().getResult().getContent().contains("羽毛球"));
        // 提取调用发生在工具完成之后、任务结束通知之前。
        var order = org.mockito.Mockito.inOrder(recorder, client);
        order.verify(recorder).afterToolExecution(any(), any(), any());
        order.verify(client).generateWithoutTools(anyList());
        order.verify(recorder).afterRun(any());
    }

    // 工具已删除的事实不能被后置提取换个 key 重新新增；验证完整主循环而不是只测校验器。
    @Test
    void shouldRejectRecreatedFactAfterConfirmedToolWrite() {
        BaseContext.setCurrentId(USER_ID);
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        // 模拟工具删除前后的索引；这里只构造写入凭据，不调用真实数据库。
        when(store.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(indexedMemory(1L, "sport")), List.of());
        when(store.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of());
        Tool writeTool = mock(Tool.class);
        when(writeTool.name()).thenReturn("delete_memory");
        when(writeTool.isMemoryWriteTool()).thenReturn(true);
        when(writeTool.execute(any())).thenReturn(ToolExecutionResult.memoryWriteSuccess("已删除运动偏好",
                new MemoryWriteReceipt(MemoryOperation.DELETE, MemoryScope.USER, USER_ID,
                        List.of(1L), List.of("sport"))));
        LlmClient client = toolThenAnswerClient("delete_memory", "{}");
        // 第一次故意绕过提示词生成重复事实，修复时才返回空候选。
        when(client.generateWithoutTools(anyList())).thenReturn(new TextLlmResponse("""
                {"memories":[{"scope":"USER","operation":"CREATE","targetMemoryRefs":[],
                "userEvidence":"喜欢羽毛球","memoryKey":"renamedSport","memoryTopic":"运动",
                "memorySummary":"喜欢羽毛球","memoryContent":"喜欢羽毛球"}]}
                """), new TextLlmResponse("{\"memories\":[]}"));
        ToolExecutionRecordingHook recorder = org.mockito.Mockito.spy(new ToolExecutionRecordingHook());
        AgentHarnessService harness = extractionHarness(client, store, mock(ConversationMemoryService.class),
                mock(MemoryConsolidationScheduler.class), List.of(writeTool), List.of(recorder),
                new MemoryReferenceRegistry());

        assertEquals("正常回答", harness.run(SESSION_ID, "请忘记我喜欢羽毛球的记忆").getAnswer());

        // 工具只执行一次；重复候选由后端拒绝，不会进入数据库写入服务。
        verify(writeTool).execute("{}");
        verify(client, org.mockito.Mockito.times(2)).generateWithoutTools(anyList());
        verify(store, never()).saveUserMemory(any());
        verify(store, never()).updateUserMemory(any());
        verify(store, never()).deleteUserMemory(any(), any());
        var finished = org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
        verify(recorder).afterRun(finished.capture());
        assertEquals(1, finished.getValue().getToolExecutions().size());
        assertEquals(MemoryOperation.DELETE, finished.getValue().getToolExecutions().getFirst()
                .getResult().memoryWriteReceipt().getOperation());
    }

    // 参数被前置 Hook 拒绝时，提取仍能看到 REJECTED，但工具和后置 Hook 都不能执行。
    @Test
    void shouldRecordRejectedCallWithoutAfterToolHook() {
        BaseContext.setCurrentId(USER_ID);
        Tool tool = queryTool();
        // 模拟被拒绝的记忆写工具，声明由 Java 实现给出而不是模型输入。
        when(tool.isMemoryWriteTool()).thenReturn(true);
        LlmClient client = toolThenAnswerClient("query", "{}");
        ToolExecutionRecordingHook recorder = org.mockito.Mockito.spy(new ToolExecutionRecordingHook());
        AgentHook rejection = new AgentHook() {
            // 模拟参数问题，允许主模型修正或正常结束。
            @Override
            public ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall call) {
                return ToolCallHookResult.reject("INVALID_ARGUMENT", "需要补充参数", true);
            }
        };
        AgentHarnessService harness = extractionHarness(client, mock(StructuredMemoryService.class),
                mock(ConversationMemoryService.class), mock(MemoryConsolidationScheduler.class),
                List.of(tool), List.of(rejection, recorder), new MemoryReferenceRegistry());

        assertEquals("正常回答", harness.run(SESSION_ID, "查询资料").getAnswer());
        verify(tool, never()).execute(any());
        verify(recorder, never()).afterToolExecution(any(), any(), any());
        verify(client).generateWithoutTools(org.mockito.ArgumentMatchers.argThat(messages ->
                messages.get(1).getContent().contains("REJECTED")));
    }

    // 记录 Hook 故障时，Harness 使用手头的真实结果补齐，不让工具重复执行。
    @Test
    void shouldRepairMissingHookRecordFromActualResult() {
        BaseContext.setCurrentId(USER_ID);
        LlmClient client = toolThenAnswerClient("query", "{}");
        Tool tool = queryTool();
        AgentHook brokenRecorder = new AgentHook() {
            // 模拟观察逻辑失败，不改变已经执行完的业务结果。
            @Override
            public void afterToolExecution(AgentRunContext context, ToolCall call, ToolExecutionResult result) {
                throw new IllegalStateException("模拟记录失败");
            }
        };
        AgentHarnessService harness = extractionHarness(client, mock(StructuredMemoryService.class),
                mock(ConversationMemoryService.class), mock(MemoryConsolidationScheduler.class),
                List.of(tool), List.of(brokenRecorder), new MemoryReferenceRegistry());

        assertEquals("正常回答", harness.run(SESSION_ID, "查询资料").getAnswer());
        verify(tool).execute("{}");
        // 普通查询仍会被补记，但不会因为记录补齐就进入提取输入。
        verify(client).generateWithoutTools(org.mockito.ArgumentMatchers.argThat(messages ->
                new tools.jackson.databind.json.JsonMapper().readTree(messages.get(1).getContent())
                        .get("toolExecutions").isEmpty()));
    }

    // 明确发现轨迹不完整时保留用户回答，但不再发起可能误写数据的自动提取。
    @Test
    void shouldSkipExtractionWhenTraceIsIncomplete() {
        BaseContext.setCurrentId(USER_ID);
        LlmClient client = toolThenAnswerClient("query", "{}");
        AgentHook incompleteRecorder = new AgentHook() {
            // 模拟已经检测到丢失操作，单条结果补齐也不能证明整轮完整。
            @Override
            public void afterToolExecution(AgentRunContext context, ToolCall call, ToolExecutionResult result) {
                context.markToolHistoryIncomplete();
            }
        };
        AgentHarnessService harness = extractionHarness(client, mock(StructuredMemoryService.class),
                mock(ConversationMemoryService.class), mock(MemoryConsolidationScheduler.class),
                List.of(queryTool()), List.of(incompleteRecorder), new MemoryReferenceRegistry());

        assertEquals("正常回答", harness.run(SESSION_ID, "查询资料").getAnswer());
        verify(client, never()).generateWithoutTools(anyList());
    }

    // 主模型先调用工具再回答，提取模型返回空候选；三个调用分别验证。
    private LlmClient toolThenAnswerClient(String toolName, String arguments) {
        LlmClient client = mock(LlmClient.class);
        when(client.generate(anyList())).thenAnswer(inv ->
                new ToolCallLlmResponse(List.of(new ToolCall("call_test", toolName,
                        copyVisibleMemoryReference(arguments, inv.getArgument(0))))))
                .thenReturn(new TextLlmResponse("正常回答"));
        when(client.generateWithoutTools(anyList())).thenReturn(new TextLlmResponse("{\"memories\":[]}"));
        return client;
    }

    // 模拟模型从本次索引复制引用；故意测试错误编号的场景不使用占位符。
    private static String copyVisibleMemoryReference(String arguments, List<LlmMessage> messages) {
        if (!arguments.contains("${CURRENT_MEMORY_REF}")) {
            return arguments;
        }
        String reference = messages.getFirst().getContent().lines()
                .filter(line -> line.startsWith("- memoryRef=")).findFirst().orElseThrow()
                .substring("- memoryRef=".length()).split("，")[0];
        return arguments.replace("${CURRENT_MEMORY_REF}", reference);
    }

    // 准备普通查询工具，用于验证结果收集与 Hook 异常分支。
    private Tool queryTool() {
        Tool tool = mock(Tool.class);
        when(tool.name()).thenReturn("query");
        when(tool.execute(any())).thenReturn(ToolExecutionResult.success("查询完成"));
        return tool;
    }

    // 使用生产构造器接入真实提取和保存服务，模型和数据库由测试替代。
    private AgentHarnessService extractionHarness(LlmClient client, StructuredMemoryService store,
                                                  ConversationMemoryService history) {
        return extractionHarness(client, store, history, mock(MemoryConsolidationScheduler.class));
    }

    // 整理依赖由测试传入，便于检查调用顺序，不在生产类中添加测试构造器。
    private AgentHarnessService extractionHarness(LlmClient client, StructuredMemoryService store,
                                                  ConversationMemoryService history, MemoryConsolidationScheduler consolidation) {
        return extractionHarness(client, store, history, consolidation, List.of(),
                List.of(new ToolExecutionRecordingHook()), new MemoryReferenceRegistry());
    }

    // 测试可替换工具和 Hook；仍使用同一套生产 Harness 与提取服务。
    private AgentHarnessService extractionHarness(LlmClient client, StructuredMemoryService store,
                                                  ConversationMemoryService history, MemoryConsolidationScheduler consolidation,
                                                  List<Tool> tools, List<AgentHook> hooks, MemoryReferenceRegistry references) {
        LearningSessionRepository repository = mock(LearningSessionRepository.class);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(repository.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        LlmRetryExecutor retry = new LlmRetryExecutor();
        var approvals = mock(com.yjjoker.learningagent.harness.memory.service.MemoryApprovalService.class);
        when(approvals.create(anyLong(), anyLong(), any(), any())).thenAnswer(invocation -> {
            var request = new com.yjjoker.learningagent.harness.memory.model.MemoryApprovalRequest();
            request.setId(1L);
            return request;
        });
        // 显式安装生产整理 Hook，验证主循环只提交通知而不直接整理。
        var installedHooks = new java.util.ArrayList<>(hooks);
        installedHooks.add(new com.yjjoker.learningagent.harness.hook.MemoryConsolidationHook(consolidation));
        return new AgentHarnessServiceImpl(client, new ToolRegistry(tools), installedHooks, history, repository,
                new ContextManager(40_000, 8_000), new InMemoryOriginalToolResultStoreImpl(), null, retry,
                store, references, new LlmMemoryExtractionService(client, retry),
                approvals, mock(com.yjjoker.learningagent.harness.approval.AgentApprovalService.class));
    }

    // 创建属于当前用户的运动记忆，供主循环与提取索引使用。
    private UserMemory indexedMemory(Long id, String key) {
        UserMemory memory = new UserMemory();
        memory.setId(id);
        memory.setUserId(USER_ID);
        memory.setMemoryKey(key);
        memory.setMemoryTopic("运动偏好");
        memory.setMemorySummary("最喜欢羽毛球");
        memory.setMemoryContent("最喜欢羽毛球");
        return memory;
    }

    // 假模型只返回文本，测试重点放在 Harness 发出的第一份上下文。
    private static class RecordingLlmClient implements LlmClient {

        private final java.util.ArrayList<List<LlmMessage>> messages = new java.util.ArrayList<>();

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            this.messages.add(List.copyOf(messages));
            return new TextLlmResponse("测试回答");
        }
    }

    // 按顺序返回工具调用和最终文本，用来验证召回工具确实经过 AgentLoop。
    private static class SequenceLlmClient implements LlmClient {

        private final Deque<LlmResponse> responses;
        private final List<List<LlmMessage>> messages = new ArrayList<>();

        private SequenceLlmClient(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            this.messages.add(List.copyOf(messages));
            LlmResponse response = responses.removeFirst();
            if (response instanceof ToolCallLlmResponse toolResponse) {
                return new ToolCallLlmResponse(toolResponse.toolCalls().stream().map(call ->
                        new ToolCall(call.id(), call.name(), copyVisibleMemoryReference(call.arguments(), messages))).toList());
            }
            return response;
        }
    }
}
