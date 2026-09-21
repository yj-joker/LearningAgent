package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("上下文压缩测试工具")
class ContextCompressionTestToolTest {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    @Test
    @DisplayName("工具返回结果超过配置上限并会被截断")
    void shouldReturnLargeResultAndBeCompacted() throws Exception {
        ContextCompressionTestTool tool = new ContextCompressionTestTool();
        ToolExecutionResult executionResult = tool.execute("{}");
        String originalContent = executionResult.getContent();

        // 先证明测试工具确实返回了足够大的文本，而不是测试本身没有覆盖压缩场景。
        assertTrue(originalContent.length() > 500);

        String originalJson = JSON_MAPPER.writeValueAsString(executionResult);
        ContextManager contextManager = new ContextManager(2_000, 500);
        List<LlmMessage> compactedMessages = contextManager.compactAfterToolExecution(List.of(
                LlmMessage.user("测试上下文压缩"),
                LlmMessage.toolResult("call_context_test", originalJson)
        ));

        JsonNode compactedJson = JSON_MAPPER.readTree(compactedMessages.get(1).getContent());
        String compactedContent = compactedJson.get("content").asString();

        assertEquals(500, compactedContent.length());
        assertTrue(compactedContent.contains("工具结果已截断"));
        assertEquals("call_context_test", compactedMessages.get(1).getToolCallId());
    }
}
