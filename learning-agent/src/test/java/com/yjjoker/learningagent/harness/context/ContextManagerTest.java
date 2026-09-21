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
        ContextManager manager = new ContextManager(260, 80);
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
        assertTrue(compactedJson.get("content").asString().length() <= 80);

        // 用户问题和 assistant 工具请求不能因为压缩而丢失。
        assertEquals("请查询资料", result.get(1).getContent());
        assertEquals("search_knowledge", result.get(2).getToolCalls().getFirst().name());

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
