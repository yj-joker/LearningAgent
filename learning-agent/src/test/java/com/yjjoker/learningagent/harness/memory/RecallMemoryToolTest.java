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
        String reference = registry.registerUserMemory(memory);

        RecallMemoryTool tool = new RecallMemoryTool(registry, memoryService);
        ToolExecutionResult result = tool.execute("{\"memoryRef\":\"" + reference + "\"}");

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

    // 新任务虽然也从序号 1 开始，旧 memoryRef 仍必须被拒绝，不能查到新目标。
    @Test
    void shouldRejectPreviousTasksReferenceWithoutQueryingAnotherMemory() {
        MemoryReferenceRegistry registry = new MemoryReferenceRegistry();
        StructuredMemoryService service = mock(StructuredMemoryService.class);
        UserMemory first = new UserMemory();
        first.setId(1L);
        first.setUserId(20L);
        UserMemory second = new UserMemory();
        second.setId(2L);
        second.setUserId(20L);
        try {
            registry.beginRun(20L, 10L);
            String oldReference = registry.registerUserMemory(first);
            registry.beginRun(20L, 10L);
            String currentReference = registry.registerUserMemory(second);
            org.junit.jupiter.api.Assertions.assertNotEquals(oldReference, currentReference);
            ToolExecutionResult result = new RecallMemoryTool(registry, service)
                    .execute("{\"memoryRef\":\"" + oldReference + "\"}");
            assertFalse(result.isSuccess());
            assertEquals("INVALID_MEMORY_REFERENCE", result.getErrorCode());
            org.mockito.Mockito.verifyNoInteractions(service);
        } finally {
            registry.clear();
        }
    }
}
