package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.memory.service.MemoryReferenceRegistry;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.tool.impl.RecallMemoryTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecallMemoryToolTest {

    @Test
    void shouldResolveMemoryRefBeforeReadingDatabaseMemory() {
        Long userId = 20L;
        Long memoryId = 998877L;

        UserMemory memory = new UserMemory();
        memory.setId(memoryId);
        memory.setUserId(userId);
        memory.setMemoryKey("sports_preference");
        memory.setMemoryTopic("personal_preference");
        memory.setMemoryContent("用户喜欢篮球");

        StructuredMemoryService memoryService = mock(StructuredMemoryService.class);
        when(memoryService.recallUserMemory(userId, memoryId)).thenReturn(memory);

        // 先建立本次 AgentLoop 的引用，再让工具解析模型传入的短引用。
        MemoryReferenceRegistry registry = new MemoryReferenceRegistry();
        registry.beginRun(userId, 10L);
        registry.registerUserMemory(memory);

        RecallMemoryTool tool = new RecallMemoryTool(registry, memoryService);
        ToolExecutionResult result = tool.execute("{\"memoryRef\":\"memory_1\"}");

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("用户喜欢篮球"));
        verify(memoryService).recallUserMemory(eq(userId), eq(memoryId));
        registry.clear();
    }

    @Test
    void shouldReturnRetryableErrorForUnknownMemoryRef() {
        StructuredMemoryService memoryService = mock(StructuredMemoryService.class);
        MemoryReferenceRegistry registry = new MemoryReferenceRegistry();
        registry.beginRun(20L, 10L);

        RecallMemoryTool tool = new RecallMemoryTool(registry, memoryService);
        ToolExecutionResult result = tool.execute("{\"memoryRef\":\"memory_99\"}");

        assertFalse(result.isSuccess());
        assertEquals("INVALID_MEMORY_REFERENCE", result.getErrorCode());
        assertTrue(result.isRetryable());
        registry.clear();
    }
}
