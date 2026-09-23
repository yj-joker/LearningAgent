package com.yjjoker.learningagent.harness.context;

import com.yjjoker.learningagent.exception.ContextWindowExceededException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("上下文管理器测试")
class ContextManagerTest {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    @Test
    @DisplayName("未超限时保留全部消息内容")
    void shouldKeepMessagesWhenContextFits() {
        ContextManager manager = new ContextManager(1_000, 100);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("系统规则"),
                LlmMessage.user("我是小明"),
                LlmMessage.assistant("你好，小明")
        );

        List<LlmMessage> result = manager.prepareForLlmRequest(messages);

        // 返回新列表可防止调用方误改原列表，消息对象本身无需复制。
        assertNotSame(messages, result);
        assertEquals("我是小明", result.get(1).getContent());
        assertEquals("你好，小明", result.get(2).getContent());
    }

    @Test
    @DisplayName("超限时只截断结构化工具结果中的 content")
    void shouldCompactOnlyStructuredToolContent() throws Exception {
        ContextManager manager = new ContextManager(360, 80);
        String largeToolResult = JSON_MAPPER.writeValueAsString(
                new ToolResultForTest(true, "资料".repeat(100), null, null, false)
        );
        List<LlmMessage> messages = List.of(
                LlmMessage.system("系统规则"),
                LlmMessage.user("请查询资料"),
                LlmMessage.assistantToolCalls(List.of(
                        new ToolCall("call_rag", "search_knowledge", "{\"query\":\"事务\"}")
                )),
                LlmMessage.toolResult("call_rag", largeToolResult)
        );

        List<LlmMessage> result = manager.compactAfterToolExecution(messages);
        LlmMessage compactedToolMessage = result.get(3);
        JsonNode compactedJson = JSON_MAPPER.readTree(compactedToolMessage.getContent());

        assertEquals("call_rag", compactedToolMessage.getToolCallId());
        assertTrue(compactedJson.get("success").asBoolean());
        assertFalse(compactedJson.get("retryable").asBoolean());
        assertTrue(compactedJson.get("content").asString().contains("工具结果已截断"));
        assertEquals("result_1", compactedJson.get("recoveryRef").asString());
        assertTrue(compactedJson.get("content").asString().length() <= 80);

        // 用户问题和 assistant 工具请求不能因为压缩而丢失。
        assertEquals("请查询资料", result.get(1).getContent());
        assertEquals("search_knowledge", result.get(2).getToolCalls().getFirst().name());
        assertTrue(compactedToolMessage.isContextReplayable());

        // 输入列表代表完整历史，ContextManager 不能在原对象上覆盖工具结果。
        assertEquals(largeToolResult, messages.get(3).getContent());
    }

    @Test
    @DisplayName("普通文本工具结果使用安全截断兜底")
    void shouldCompactPlainTextToolResult() {
        ContextManager manager = new ContextManager(100, 50);
        List<LlmMessage> messages = List.of(
                LlmMessage.user("搜索"),
                LlmMessage.toolResult("call_web", "网页内容".repeat(40))
        );

        List<LlmMessage> result = manager.prepareForLlmRequest(messages);

        assertEquals(50, result.get(1).getContent().length());
        assertTrue(result.get(1).getContent().contains("工具结果已截断"));
    }

    @Test
    @DisplayName("工具结果压缩后仍超限时明确终止")
    void shouldFailWhenNonToolMessagesAlreadyExceedLimit() {
        ContextManager manager = new ContextManager(30, 10);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("系统规则".repeat(10)),
                LlmMessage.user("用户问题")
        );

        ContextWindowExceededException exception = assertThrows(
                ContextWindowExceededException.class,
                () -> manager.prepareForLlmRequest(messages)
        );

        assertTrue(exception.getMessage().contains("摘要压缩功能尚未启用"));
    }

    @Test
    @DisplayName("恢复预算同时限制次数和累计字符数")
    void shouldLimitRecoveryCallsAndCharacters() {
        ContextManager manager = new ContextManager(1_000, 100, 2, 300, 0.95);

        assertTrue(manager.hasRecoveryCallCapacity(0));
        assertTrue(manager.hasRecoveryCallCapacity(1));
        assertFalse(manager.hasRecoveryCallCapacity(2));

        assertTrue(manager.hasRecoveryCharacterCapacity(200, 100));
        assertFalse(manager.hasRecoveryCharacterCapacity(200, 101));
    }

    @Test
    @DisplayName("所有工具结果都在百分之九十五安全水位触发压缩")
    void shouldApplySafeContextRatioToEveryToolResult() {
        ContextManager manager = new ContextManager(100, 20, 2, 300, 0.95);
        List<LlmMessage> messages = List.of(
                LlmMessage.user("问".repeat(50)),
                LlmMessage.toolResult("c", "结果".repeat(19))
        );

        // 原消息没有超过 100 字符硬上限，但超过 95% 安全水位，因此仍需压缩工具正文。
        assertTrue(manager.estimateCharacters(messages) <= 100);
        assertTrue(manager.estimateCharacters(messages) > 95);

        List<LlmMessage> compacted = manager.prepareForLlmRequest(messages);

        assertEquals(20, compacted.get(1).getContent().length());
        assertEquals("结果".repeat(19), compacted.get(1).getOriginalContent());
        assertEquals(compacted.get(1).getContent(), compacted.get(1).getContextContent());
    }

    @Test
    @DisplayName("触发压缩后尽量达到目标水位，而不是刚低于安全上限就停止")
    void shouldStopCompactingAfterReachingTargetRatio() {
        ContextManager manager = new ContextManager(1_000, 200, 2, 300, 0.95, 0.80);
        String oldToolContent = "旧资料".repeat(400);
        String recentToolContent = "新资料".repeat(100);
        List<LlmMessage> messages = List.of(
                LlmMessage.user("用户问题".repeat(10)),
                LlmMessage.toolResult("old", oldToolContent),
                LlmMessage.toolResult("recent", recentToolContent)
        );

        int before = manager.estimateCharacters(messages);
        List<LlmMessage> compacted = manager.prepareForLlmRequest(messages);
        int after = manager.estimateCharacters(compacted);

        assertTrue(before > 950);
        assertTrue(after <= 800);
        assertTrue(compacted.get(1).getContent().contains("工具结果已截断"));
        // 旧工具已经释放出足够空间，因此较新的工具结果不必继续压缩。
        assertEquals(recentToolContent, compacted.get(2).getContent());
    }

    @Test
    @DisplayName("工具结果压缩后仍超限时摘要旧历史并保留当前问题")
    void shouldSummarizeOldHistoryAfterToolCompactionStillExceedsLimit() {
        ContextManager manager = new ContextManager(120, 20);
        LlmMessage currentUserMessage = LlmMessage.user("当前问题");
        List<LlmMessage> messages = List.of(
                LlmMessage.system("系统规则"),
                LlmMessage.user("旧问题".repeat(30)),
                LlmMessage.assistant("旧回答".repeat(30)),
                currentUserMessage
        );

        List<LlmMessage> result = manager.prepareForLlmRequest(
                messages,
                new RecoveryReferenceRegistry(),
                oldMessages -> {
                    assertEquals(2, oldMessages.size());
                    return "用户之前讨论过 Java 学习。";
                },
                3
        );

        assertEquals("系统规则", result.get(0).getContent());
        assertTrue(result.get(1).isSummary());
        assertTrue(result.contains(currentUserMessage));
        assertTrue(manager.estimateCharacters(result) <= 114);
    }

    @Test
    @DisplayName("摘要移除旧工具后清理过期 recoveryRef")
    void shouldRemoveRecoveryReferencesForToolsOutsideCurrentContext() {
        ContextManager manager = new ContextManager(1_000, 100);
        RecoveryReferenceRegistry registry = new RecoveryReferenceRegistry();
        assertEquals("result_1", registry.register("call_old"));
        assertEquals("result_2", registry.register("call_recent"));

        List<LlmMessage> messages = List.of(
                LlmMessage.system("系统规则"),
                LlmMessage.summary("旧历史摘要"),
                LlmMessage.toolResult("call_recent", "最近工具结果")
                        .withContextContent("最近工具结果的上下文副本")
        );

        manager.prepareForLlmRequest(messages, registry);

        assertEquals("call_recent", registry.resolve("result_2"));
        assertNull(registry.resolve("result_1"));
    }

    // 仅用于生成与真实 ToolExecutionResult 相同结构的 JSON，避免测试依赖手写转义字符串。
    private static class ToolResultForTest {

        public final boolean success;
        public final String content;
        public final String errorCode;
        public final String message;
        public final boolean retryable;

        private ToolResultForTest(boolean success,
                                  String content,
                                  String errorCode,
                                  String message,
                                  boolean retryable) {
            this.success = success;
            this.content = content;
            this.errorCode = errorCode;
            this.message = message;
            this.retryable = retryable;
        }
    }
}
