package com.yjjoker.learningagent.harness;

import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.harness.hook.AgentHook;
import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.hook.ToolCallHookResult;
import com.yjjoker.learningagent.harness.hook.ToolArgumentValidationHook;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.tool.impl.GetOriginalToolResultTool;
import com.yjjoker.learningagent.harness.impl.AgentHarnessServiceImpl;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.memory.ConversationMemoryService;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Agent Harness 循环测试")
class AgentHarnessTest {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();
    private static final Long SESSION_ID = 10L;
    private static final Long USER_ID = 20L;

    @BeforeEach
    void setCurrentUser() {
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void clearCurrentUser() {
        BaseContext.removeCurrentId();
    }

    @Test
    @DisplayName("模型直接返回文本时不执行工具")
    void shouldReturnDirectTextResponse() {
        // 假模型的第一次结果就是最终文本，用来模拟不需要任何外部数据的普通问答。
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new TextLlmResponse("模拟的模型回复")
        );
        RecordingTool tool = new RecordingTool("find_all_users", "张三, 李四");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool));
        AgentHarnessService harness = createHarness(fakeLlmClient, toolRegistry, List.of());

        String reply = harness.run(SESSION_ID, "什么是数据库事务？");

        assertEquals("模拟的模型回复", reply);
        assertEquals(1, fakeLlmClient.receivedMessages.size());

        // 第一次请求也必须先发送 system，再发送 user，不能把系统规则当成用户说的话。
        List<LlmMessage> firstRequest = fakeLlmClient.receivedMessages.getFirst();
        assertEquals(2, firstRequest.size());
        assertEquals("system", firstRequest.get(0).getRole());
        assertTrue(firstRequest.get(0).getContent().contains("不能编造数据"));
        assertEquals("user", firstRequest.get(1).getRole());
        assertEquals("什么是数据库事务？", firstRequest.get(1).getContent());
        assertEquals(0, tool.executeCount);
    }

    @Test
    @DisplayName("模型请求工具时执行工具并把结果交回模型")
    void shouldExecuteToolAndSendResultBackToLlm() {
        ToolCall toolCall = new ToolCall("call_123", "find_all_users", "{}");

        // 第一次 generate 要求调用工具，第二次 generate 才返回基于工具数据形成的最终回答。
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(toolCall)),
                new TextLlmResponse("目前有两位用户：张三和李四。")
        );
        RecordingTool tool = new RecordingTool("find_all_users", "张三, 李四");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool));
        AgentHarnessService harness = createHarness(fakeLlmClient, toolRegistry, List.of());

        String reply = harness.run(SESSION_ID, "系统中有哪些用户？");

        assertEquals("目前有两位用户：张三和李四。", reply);
        assertEquals(1, tool.executeCount);
        assertEquals("{}", tool.receivedInput);
        assertEquals(2, fakeLlmClient.receivedMessages.size());

        // 第二次请求必须保留 system，并带上 user、assistant 工具请求和 tool 工具结果。
        List<LlmMessage> secondRequest = fakeLlmClient.receivedMessages.get(1);
        assertEquals(4, secondRequest.size());
        assertEquals("system", secondRequest.get(0).getRole());
        assertEquals("user", secondRequest.get(1).getRole());
        assertEquals("assistant", secondRequest.get(2).getRole());
        assertEquals("call_123", secondRequest.get(2).getToolCalls().getFirst().id());
        assertEquals("tool", secondRequest.get(3).getRole());
        assertEquals("call_123", secondRequest.get(3).getToolCallId());

        // Harness 发送的是结构化 JSON，不再让模型猜测普通字符串表示成功还是失败。
        JsonNode toolResult = JSON_MAPPER.readTree(secondRequest.get(3).getContent());
        assertTrue(toolResult.get("success").asBoolean());
        assertEquals("张三, 李四", toolResult.get("content").asString());
    }

    @Test
    @DisplayName("可修正的工具错误会作为 tool 消息交给模型")
    void shouldSendRecoverableToolFailureBackToLlm() {
        ToolCall toolCall = new ToolCall("call_invalid", "find_user_by_name", "{}");
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(toolCall)),
                new TextLlmResponse("请告诉我要查询的用户名。")
        );
        RecordingTool tool = new RecordingTool(
                "find_user_by_name",
                ToolExecutionResult.failure("INVALID_ARGUMENT", "缺少 username", true)
        );
        AgentHarnessService harness = createHarness(
                fakeLlmClient,
                new ToolRegistry(List.of(tool)),
                List.of()
        );

        String reply = harness.run(SESSION_ID, "帮我查一个用户");

        assertEquals("请告诉我要查询的用户名。", reply);
        JsonNode toolResult = JSON_MAPPER.readTree(
                fakeLlmClient.receivedMessages.get(1).get(3).getContent()
        );
        assertFalse(toolResult.get("success").asBoolean());
        assertEquals("INVALID_ARGUMENT", toolResult.get("errorCode").asString());
        assertTrue(toolResult.get("retryable").asBoolean());
    }

    @Test
    @DisplayName("工具系统异常会终止请求而不会把内部错误发给模型")
    void shouldStopWhenToolHasSystemFailure() {
        ToolCall toolCall = new ToolCall("call_failed", "broken_tool", "{}");
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(toolCall))
        );
        FakeConversationMemoryService memoryService = new FakeConversationMemoryService();
        AgentHarnessService harness = createHarness(
                fakeLlmClient,
                new ToolRegistry(List.of(new ThrowingTool())),
                List.of(),
                memoryService
        );

        LearningAgentServiceException exception = assertThrows(
                LearningAgentServiceException.class,
                () -> harness.run(SESSION_ID, "执行故障工具")
        );

        assertEquals("工具执行失败，请稍后重试", exception.getMessage());
        assertEquals(1, fakeLlmClient.receivedMessages.size());
        assertTrue(memoryService.savedMessages.isEmpty());
    }

    @Test
    @DisplayName("模型持续请求工具时在安全上限处停止")
    void shouldStopAfterMaximumToolRounds() {
        ToolCallLlmResponse repeatedToolCall = new ToolCallLlmResponse(
                List.of(new ToolCall("call_repeat", "find_all_users", "{}"))
        );

        // 准备六次相同工具请求：前五轮允许执行，第六轮会触发 Harness 的循环保护。
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                repeatedToolCall,
                repeatedToolCall,
                repeatedToolCall,
                repeatedToolCall,
                repeatedToolCall,
                repeatedToolCall
        );
        RecordingTool tool = new RecordingTool("find_all_users", "张三");
        AgentHarnessService harness = createHarness(
                fakeLlmClient,
                new ToolRegistry(List.of(tool)),
                List.of()
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> harness.run(SESSION_ID, "一直查用户")
        );

        assertEquals("Harness 超过最多 5 轮工具调用，已停止继续执行", exception.getMessage());
        assertEquals(5, tool.executeCount);
    }

    @Test
    @DisplayName("工具执行前后和任务结束时会按顺序通知 Hook")
    void shouldNotifyHooksDuringAgentRun() {
        ToolCall toolCall = new ToolCall("call_hook", "test_learning_tool", "{}");
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(toolCall)),
                new TextLlmResponse("工具处理完成")
        );
        RecordingTool tool = new RecordingTool("test_learning_tool", "测试结果");
        RecordingAgentHook hook = new RecordingAgentHook();
        AgentHarnessService harness = createHarness(
                fakeLlmClient,
                new ToolRegistry(List.of(tool)),
                List.of(hook)
        );

        String reply = harness.run(SESSION_ID, "执行测试学习工具");

        assertEquals("工具处理完成", reply);
        assertEquals(
                List.of("before:test_learning_tool", "afterTool:test_learning_tool:true", "afterRun"),
                hook.events
        );
        assertTrue(hook.completedContext.isCompleted());
        assertTrue(hook.completedContext.isSuccessful());
        assertEquals(List.of("test_learning_tool"), hook.completedContext.getExecutedToolNames());
    }

    @Test
    @DisplayName("可重试的 Hook 拒绝结果会交给模型修正")
    void shouldSendRetryableHookRejectionBackToLlm() {
        // 这里故意让模型返回缺少右花括号的非法 JSON。
        ToolCall invalidToolCall = new ToolCall("call_invalid_json", "test_learning_tool", "{\"courseId\":1");
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(invalidToolCall)),
                new TextLlmResponse("我已经重新检查参数，请补充课程编号。")
        );
        RecordingTool tool = new RecordingTool("test_learning_tool", "不应该得到这个结果");
        RecordingAgentHook recordingHook = new RecordingAgentHook();
        AgentHarnessService harness = createHarness(
                fakeLlmClient,
                new ToolRegistry(List.of(tool)),
                List.of(new ToolArgumentValidationHook(), recordingHook)
        );

        String reply = harness.run(SESSION_ID, "执行测试学习工具");

        assertEquals("我已经重新检查参数，请补充课程编号。", reply);
        // executeCount 为 0 证明 Hook 拒绝后，工具没有任何执行机会。
        assertEquals(0, tool.executeCount);

        // 参数 Hook 拒绝后，后面的 before 和 afterTool 都没有执行，只有任务级 afterRun 执行。
        assertEquals(List.of("afterRun"), recordingHook.events);

        JsonNode rejectedResult = JSON_MAPPER.readTree(
                fakeLlmClient.receivedMessages.get(1).get(3).getContent()
        );
        assertFalse(rejectedResult.get("success").asBoolean());
        assertEquals("INVALID_TOOL_ARGUMENTS", rejectedResult.get("errorCode").asString());
        assertTrue(rejectedResult.get("retryable").asBoolean());
    }

    @Test
    @DisplayName("不可重试的 Hook 拒绝结果会直接返回用户")
    void shouldReturnNonRetryableHookRejectionDirectlyToUser() {
        ToolCall toolCall = new ToolCall("call_forbidden", "test_learning_tool", "{}");
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(toolCall))
        );
        RecordingTool tool = new RecordingTool("test_learning_tool", "不应该得到这个结果");
        RecordingAgentHook recordingHook = new RecordingAgentHook();
        AgentHook permissionHook = new AgentHook() {
            @Override
            public ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall ignored) {
                return ToolCallHookResult.reject(
                        "TOOL_ACCESS_DENIED",
                        "当前用户没有权限执行该操作",
                        false
                );
            }
        };
        AgentHarnessService harness = createHarness(
                fakeLlmClient,
                new ToolRegistry(List.of(tool)),
                List.of(permissionHook, recordingHook)
        );

        String reply = harness.run(SESSION_ID, "执行无权限的工具");

        assertEquals("当前用户没有权限执行该操作", reply);
        assertEquals(0, tool.executeCount);
        // 只有一次请求说明 Harness 没有把不可恢复问题再次发送给模型。
        assertEquals(1, fakeLlmClient.receivedMessages.size());
        // 权限 Hook 拒绝后，后续 before 和 afterTool 不执行；整个任务结束时仍执行 afterRun。
        assertEquals(List.of("afterRun"), recordingHook.events);
    }

    @Test
    @DisplayName("历史消息会发送给模型且只保存本轮新增消息")
    void shouldLoadHistoryAndSaveOnlyCurrentTurn() {
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new TextLlmResponse("它的四个特性是原子性、一致性、隔离性和持久性。")
        );
        FakeConversationMemoryService memoryService = new FakeConversationMemoryService(List.of(
                LlmMessage.user("什么是数据库事务？"),
                LlmMessage.assistant("事务是一组不可分割的数据库操作。")
        ));
        AgentHarnessService harness = createHarness(
                fakeLlmClient,
                new ToolRegistry(List.of()),
                List.of(),
                memoryService
        );

        String reply = harness.run(SESSION_ID, "它有哪些特性？");

        assertEquals("它的四个特性是原子性、一致性、隔离性和持久性。", reply);

        // 请求顺序应为 system、旧历史、本轮 user，System Prompt 不来自数据库。
        List<LlmMessage> request = fakeLlmClient.receivedMessages.getFirst();
        assertEquals(List.of("system", "user", "assistant", "user"),
                request.stream().map(LlmMessage::getRole).toList());
        assertEquals("它有哪些特性？", request.getLast().getContent());

        // 旧历史已经存在于数据库，本轮只追加新的 user 和最终 assistant。
        assertEquals(List.of("user", "assistant"),
                memoryService.savedMessages.stream().map(LlmMessage::getRole).toList());
        assertEquals("它有哪些特性？", memoryService.savedMessages.getFirst().getContent());
        assertEquals(reply, memoryService.savedMessages.getLast().getContent());
    }

    @Test
    @DisplayName("Harness压缩发送副本但完整保存工具结果")
    void shouldCompactOnlyWorkingMessagesAndKeepFullPersistenceMessage() throws Exception {
        ToolCall toolCall = new ToolCall("call_large", "find_all_users", "{}");
        String largeResult = "用户资料".repeat(1_000);
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(toolCall)),
                new TextLlmResponse("我已经整理好用户资料。")
        );
        RecordingTool tool = new RecordingTool("find_all_users", largeResult);
        FakeConversationMemoryService memoryService = new FakeConversationMemoryService();
        AgentHarnessService harness = new AgentHarnessServiceImpl(
                fakeLlmClient,
                new ToolRegistry(List.of(tool)),
                List.of(),
                memoryService,
                new ActiveLearningSessionRepository(),
                new ContextManager(2_000, 80)
        );

        harness.run(SESSION_ID, "查询所有用户");

        // 第二次请求进入模型前已经压缩，避免发送完整的大型工具结果。
        String compactedContent = fakeLlmClient.receivedMessages.get(1).get(3).getContent();
        assertTrue(compactedContent.contains("工具结果已截断"));

        LlmMessage persistedToolMessage = memoryService.savedMessages.stream()
                .filter(message -> "tool".equals(message.getRole()))
                .findFirst()
                .orElseThrow();

        // 同一条持久化消息同时携带完整原文和以后发给模型的压缩副本。
        String persistedOriginalContent = JSON_MAPPER.readTree(
                persistedToolMessage.getOriginalContent()
        ).get("content").asString();
        assertEquals(largeResult, persistedOriginalContent);
        assertTrue(persistedToolMessage.getContextContent().contains("工具结果已截断"));
    }

    @Test
    @DisplayName("模型可以通过工具按片段恢复被截断的原始结果")
    void shouldRestoreOriginalToolResultWhenModelRequestsIt() {
        String largeResult = "ORIGINAL_DETAIL:" + "原始资料".repeat(500);
        InMemoryOriginalToolResultStoreImpl resultStore = new InMemoryOriginalToolResultStoreImpl();
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(
                        new ToolCall("call_large", "find_all_users", "{}")
                )),
                new ToolCallLlmResponse(List.of(
                        new ToolCall(
                                "call_restore",
                                "get_original_tool_result",
                                "{\"recoveryRef\":\"result_1\",\"offset\":0,\"limit\":100}"
                        )
                )),
                new TextLlmResponse("我已读取原始资料片段。")
        );

        ToolRegistry registry = new ToolRegistry(List.of(
                new RecordingTool("find_all_users", largeResult),
                new GetOriginalToolResultTool(resultStore)
        ));
        FakeConversationMemoryService memoryService = new FakeConversationMemoryService();
        AgentHarnessService harness = new AgentHarnessServiceImpl(
                fakeLlmClient,
                registry,
                List.of(),
                memoryService,
                new ActiveLearningSessionRepository(),
                new ContextManager(2_000, 250),
                resultStore
        );

        String answer = harness.run(SESSION_ID, "查询资料并在需要时读取原始细节");

        assertEquals("我已读取原始资料片段。", answer);
        // 第二次请求仍然只看到压缩后的第一次工具结果。
        assertTrue(fakeLlmClient.receivedMessages.get(1).stream()
                .anyMatch(message -> "tool".equals(message.getRole())
                        && message.getContent().contains("工具结果已截断")
                        && message.getContent().contains("恢复引用=result_1")));
        // 第三次请求包含恢复工具返回的原始片段。
        assertTrue(fakeLlmClient.receivedMessages.get(2).stream()
                .anyMatch(message -> "tool".equals(message.getRole())
                        && message.getContent().contains("ORIGINAL_DETAIL:")));

        // 恢复工具的 assistant/tool 消息完整保存，但标记为不可进入未来上下文。
        List<LlmMessage> recoveryMessages = memoryService.savedMessages.stream()
                .filter(message -> !message.isContextReplayable())
                .toList();
        assertEquals(List.of("assistant", "tool"),
                recoveryMessages.stream().map(LlmMessage::getRole).toList());
    }

    @Test
    @DisplayName("恢复工具超过单轮调用次数后返回失败")
    void shouldRejectRecoveryAfterCallLimit() throws Exception {
        String largeResult = "原始内容".repeat(500);
        InMemoryOriginalToolResultStoreImpl resultStore = new InMemoryOriginalToolResultStoreImpl();
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(new ToolCall("call_source", "source_tool", "{}"))),
                recoveryResponse("call_restore_1", 0, 20),
                recoveryResponse("call_restore_2", 20, 20),
                recoveryResponse("call_restore_3", 40, 20),
                new TextLlmResponse("恢复次数测试结束")
        );
        ToolRegistry registry = new ToolRegistry(List.of(
                new RecordingTool("source_tool", largeResult),
                new GetOriginalToolResultTool(resultStore)
        ));
        AgentHarnessService harness = new AgentHarnessServiceImpl(
                fakeLlmClient,
                registry,
                List.of(),
                new FakeConversationMemoryService(),
                new ActiveLearningSessionRepository(),
                new ContextManager(4_000, 300, 2, 1_000, 0.95),
                resultStore
        );

        harness.run(SESSION_ID, "测试恢复次数限制");

        JsonNode rejectedResult = findToolResult(fakeLlmClient.receivedMessages.get(4), "call_restore_3");
        assertFalse(rejectedResult.get("success").asBoolean());
        assertEquals("RECOVERY_CALL_LIMIT_EXCEEDED", rejectedResult.get("errorCode").asString());
    }

    @Test
    @DisplayName("恢复内容超过单轮累计字符数后返回失败")
    void shouldRejectRecoveryAfterCharacterLimit() throws Exception {
        String largeResult = "原始内容".repeat(500);
        InMemoryOriginalToolResultStoreImpl resultStore = new InMemoryOriginalToolResultStoreImpl();
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(new ToolCall("call_source", "source_tool", "{}"))),
                recoveryResponse("call_restore_1", 0, 100),
                recoveryResponse("call_restore_2", 100, 100),
                new TextLlmResponse("恢复字符测试结束")
        );
        ToolRegistry registry = new ToolRegistry(List.of(
                new RecordingTool("source_tool", largeResult),
                new GetOriginalToolResultTool(resultStore)
        ));
        AgentHarnessService harness = new AgentHarnessServiceImpl(
                fakeLlmClient,
                registry,
                List.of(),
                new FakeConversationMemoryService(),
                new ActiveLearningSessionRepository(),
                new ContextManager(4_000, 300, 3, 250, 0.95),
                resultStore
        );

        harness.run(SESSION_ID, "测试恢复字符限制");

        JsonNode rejectedResult = findToolResult(fakeLlmClient.receivedMessages.get(3), "call_restore_2");
        assertFalse(rejectedResult.get("success").asBoolean());
        assertEquals("RECOVERY_CHARACTER_LIMIT_EXCEEDED", rejectedResult.get("errorCode").asString());
    }

    @Test
    @DisplayName("恢复工具和普通工具使用同一套上下文压缩流程")
    void shouldCompactRecoveryWithSharedContextPolicy() throws Exception {
        String userMessage = "测试恢复安全水位";
        InMemoryOriginalToolResultStoreImpl resultStore = new InMemoryOriginalToolResultStoreImpl();
        resultStore.save("call_source", "原始资料".repeat(100));
        GetOriginalToolResultTool recoveryTool = new GetOriginalToolResultTool(resultStore);
        ToolCall recoveryCall = new ToolCall(
                "call_restore",
                "get_original_tool_result",
                "{\"toolCallId\":\"call_source\",\"offset\":0,\"limit\":100}"
        );

        // 按真实消息结构计算未压缩结果大小，让它处于硬上限内但超过 95% 安全水位。
        ToolExecutionResult projectedResult = recoveryTool.execute(recoveryCall.arguments());
        LlmMessage projectedToolMessage = LlmMessage.toolResult(
                recoveryCall.id(),
                JSON_MAPPER.writeValueAsString(projectedResult),
                false
        );
        List<LlmMessage> projectedMessages = List.of(
                LlmMessage.system(AgentSystemPrompt.CONTENT),
                LlmMessage.user(userMessage),
                LlmMessage.assistantToolCalls(List.of(recoveryCall), false),
                projectedToolMessage
        );
        int projectedCharacters = new ContextManager(10_000, 500)
                .estimateCharacters(projectedMessages);

        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(recoveryCall)),
                new TextLlmResponse("安全水位测试结束")
        );
        AgentHarnessService harness = new AgentHarnessServiceImpl(
                fakeLlmClient,
                new ToolRegistry(List.of(recoveryTool)),
                List.of(),
                new FakeConversationMemoryService(),
                new ActiveLearningSessionRepository(),
                new ContextManager(projectedCharacters, 40, 2, 1_000, 0.95),
                resultStore
        );

        String answer = harness.run(SESSION_ID, userMessage);

        assertEquals("安全水位测试结束", answer);
        JsonNode compactedResult = findToolResult(fakeLlmClient.receivedMessages.get(1), "call_restore");
        assertTrue(compactedResult.get("success").asBoolean());
        assertTrue(compactedResult.get("content").asString().contains("工具结果已截断"));
    }

    private ToolCallLlmResponse recoveryResponse(String callId, int offset, int limit) {
        return new ToolCallLlmResponse(List.of(new ToolCall(
                callId,
                "get_original_tool_result",
                "{\"toolCallId\":\"call_source\",\"offset\":" + offset + ",\"limit\":" + limit + "}"
        )));
    }

    private JsonNode findToolResult(List<LlmMessage> messages, String toolCallId) throws Exception {
        String content = messages.stream()
                .filter(message -> "tool".equals(message.getRole()))
                .filter(message -> toolCallId.equals(message.getToolCallId()))
                .findFirst()
                .orElseThrow()
                .getContent();
        return JSON_MAPPER.readTree(content);
    }

    @Test
    @DisplayName("非会话所属用户不能读取历史或调用模型")
    void shouldRejectUserWhoDoesNotOwnSession() {
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new TextLlmResponse("不应该返回")
        );
        FakeConversationMemoryService memoryService = new FakeConversationMemoryService();
        AgentHarnessService harness = new AgentHarnessServiceImpl(
                fakeLlmClient,
                new ToolRegistry(List.of()),
                List.of(),
                memoryService,
                new ActiveLearningSessionRepository(USER_ID + 1, LearningSessionStatusEnum.ACTIVE)
        );

        LearningSessionStatusException exception = assertThrows(
                LearningSessionStatusException.class,
                () -> harness.run(SESSION_ID, "读取其他用户的会话")
        );

        assertEquals("无权访问该学习会话", exception.getMessage());
        assertEquals(0, memoryService.loadCount);
        assertTrue(fakeLlmClient.receivedMessages.isEmpty());
    }

    private AgentHarnessService createHarness(FakeLlmClient llmClient,
                                               ToolRegistry toolRegistry,
                                               List<AgentHook> hooks) {
        return createHarness(llmClient, toolRegistry, hooks, new FakeConversationMemoryService());
    }

    private AgentHarnessService createHarness(FakeLlmClient llmClient,
                                               ToolRegistry toolRegistry,
                                               List<AgentHook> hooks,
                                               FakeConversationMemoryService memoryService) {
        return new AgentHarnessServiceImpl(
                llmClient,
                toolRegistry,
                hooks,
                memoryService,
                new ActiveLearningSessionRepository()
        );
    }

    // 假模型按顺序返回预先准备好的结果，并保存每次收到的完整消息列表。
    // 它不会访问网络，因此测试可以只验证 Harness 是否正确控制循环。
    private static class FakeLlmClient implements LlmClient {

        private final Deque<LlmResponse> responses;
        private final List<List<LlmMessage>> receivedMessages = new ArrayList<>();

        private FakeLlmClient(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            // List.copyOf 保存本轮请求的只读快照，防止 Harness 后续追加消息改变已经记录的历史。
            receivedMessages.add(List.copyOf(messages));

            if (responses.isEmpty()) {
                throw new IllegalStateException("测试没有准备足够的模型响应");
            }
            return responses.removeFirst();
        }
    }

    // 测试记忆服务把预设历史交给 Harness，并记录 Harness 最终要求保存的本轮消息。
    private static class FakeConversationMemoryService implements ConversationMemoryService {

        private final List<LlmMessage> history;
        private final List<LlmMessage> savedMessages = new ArrayList<>();
        private int loadCount;

        private FakeConversationMemoryService() {
            this(List.of());
        }

        private FakeConversationMemoryService(List<LlmMessage> history) {
            this.history = List.copyOf(history);
        }

        @Override
        public List<LlmMessage> loadHistory(Long sessionId) {
            loadCount++;
            return history;
        }

        @Override
        public void appendMessage(Long sessionId, LlmMessage message) {
            savedMessages.add(message);
        }

        @Override
        public void appendMessages(Long sessionId, List<LlmMessage> messages) {
            savedMessages.addAll(messages);
        }

        @Override
        public void updateToolContextCopies(Long sessionId, List<LlmMessage> messages) {
            // 单元测试使用内存列表，不需要模拟数据库 UPDATE。
        }

        @Override
        public void replaceReplayableHistoryWithSummary(Long sessionId, LlmMessage summaryMessage) {
            // 单元测试不连接数据库，只记录摘要消息，验证 Harness 确实触发了持久化入口。
            savedMessages.add(summaryMessage);
        }
    }

    // 为 Harness 测试提供属于当前用户且状态为 ACTIVE 的学习会话。
    private static class ActiveLearningSessionRepository implements LearningSessionRepository {

        private final Long ownerId;
        private final LearningSessionStatusEnum status;

        private ActiveLearningSessionRepository() {
            this(USER_ID, LearningSessionStatusEnum.ACTIVE);
        }

        private ActiveLearningSessionRepository(Long ownerId, LearningSessionStatusEnum status) {
            this.ownerId = ownerId;
            this.status = status;
        }

        @Override
        public int createSession(LearningSession learningSession) {
            throw new UnsupportedOperationException("测试不需要创建会话");
        }

        @Override
        public Optional<LearningSession> findSessionById(Long sessionId) {
            LearningSession session = new LearningSession();
            session.setId(sessionId);
            session.setUserId(ownerId);
            session.setStatus(status);
            return Optional.of(session);
        }

        @Override
        public int updateSession(LearningSession learningSession) {
            throw new UnsupportedOperationException("测试不需要更新会话");
        }
    }

    // 这个假工具记录执行次数和输入参数，并返回固定数据，便于验证 Harness 是否真的调用了它。
    private static class RecordingTool implements Tool {

        private final String name;
        private final ToolExecutionResult result;
        private int executeCount;
        private String receivedInput;

        private RecordingTool(String name, String result) {
            this(name, ToolExecutionResult.success(result));
        }

        private RecordingTool(String name, ToolExecutionResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public ToolExecutionResult execute(String input) {
            executeCount++;
            receivedInput = input;
            return result;
        }
    }

    private static class ThrowingTool implements Tool {

        @Override
        public String name() {
            return "broken_tool";
        }

        @Override
        public String description() {
            return "用于验证系统异常的测试工具";
        }

        @Override
        public ToolExecutionResult execute(String input) {
            throw new IllegalStateException("模拟数据库连接失败");
        }
    }

    // 测试 Hook 只记录回调顺序，证明工具前置、工具后置和任务结束是三个不同节点。
    private static class RecordingAgentHook implements AgentHook {

        private final List<String> events = new ArrayList<>();
        private AgentRunContext completedContext;

        @Override
        public ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall toolCall) {
            events.add("before:" + toolCall.name());
            return ToolCallHookResult.allow();
        }

        @Override
        public void afterToolExecution(AgentRunContext context,
                                       ToolCall toolCall,
                                       ToolExecutionResult result) {
            events.add("afterTool:" + toolCall.name() + ":" + result.isSuccess());
        }

        @Override
        public void afterRun(AgentRunContext context) {
            events.add("afterRun");
            completedContext = context;
        }
    }
}
