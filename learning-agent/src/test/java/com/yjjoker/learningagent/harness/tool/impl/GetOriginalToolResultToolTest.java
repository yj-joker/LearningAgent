package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("原始工具结果读取工具测试")
class GetOriginalToolResultToolTest {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    @Test
    @DisplayName("可以按调用 ID 和偏移量读取原始片段")
    void shouldReadOriginalResultByChunk() throws Exception {
        InMemoryOriginalToolResultStoreImpl store = new InMemoryOriginalToolResultStoreImpl();
        store.save("call_rag", "ABCDEFGHIJKL");
        GetOriginalToolResultTool tool = new GetOriginalToolResultTool(store);

        ToolExecutionResult result = tool.execute(
                "{\"toolCallId\":\"call_rag\",\"offset\":2,\"limit\":4}"
        );

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("totalLength=12"));
        assertTrue(result.getContent().endsWith("CDEF"));
    }

    @Test
    @DisplayName("读取不存在的调用 ID 时返回不可重试错误")
    void shouldReportMissingOriginalResult() throws Exception {
        GetOriginalToolResultTool tool = new GetOriginalToolResultTool(
                new InMemoryOriginalToolResultStoreImpl()
        );

        ToolExecutionResult result = tool.execute("{\"toolCallId\":\"missing\"}");

        assertTrue(!result.isSuccess());
        assertEquals("ORIGINAL_TOOL_RESULT_NOT_FOUND", result.getErrorCode());
        assertTrue(!result.isRetryable());
    }
}
