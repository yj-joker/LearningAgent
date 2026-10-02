package com.yjjoker.learningagent.harness.approval;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.context.RecoveryReferenceRegistry;
import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.MemoryReferenceRegistry;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

// 验证检查点经过 JSON 存取后仍可恢复，覆盖纯内存测试容易漏掉的字段和时间类型。
class AgentRunCheckpointTest {
    private final JsonMapper json = new JsonMapper();

    // 保留原文、压缩副本、摘要标记和不可重放消息，不能用 loadHistory 代替检查点。
    @Test
    void retainsCompleteMessageProtocolAndBudgets() {
        AgentRunCheckpoint source = new AgentRunCheckpoint();
        source.setMessages(List.of(LlmMessage.system("规则"), LlmMessage.summary("早期事实"), LlmMessage.user("问题"),
                LlmMessage.assistantToolCalls(List.of(new ToolCall("call-original", "read", "{}")), false),
                LlmMessage.toolResultWithContextContent("call-original", "完整原文", "压缩副本", false)));
        source.setCurrentRunStartIndex(2);
        source.setCompletedToolRounds(4);
        source.setCompletedRecoveryCalls(2);
        source.setRecoveredCharacters(1200);
        source.setSummaryUsed(true);
        AgentRunCheckpoint restored = roundTrip(source);
        assertTrue(restored.getMessages().get(1).isSummary());
        assertFalse(restored.getMessages().get(3).isContextReplayable());
        LlmMessage result = restored.getMessages().getLast();
        assertFalse(result.isContextReplayable());
        assertEquals("call-original", result.getToolCallId());
        assertEquals("完整原文", result.getOriginalContent());
        assertEquals("压缩副本", result.getContent());
        assertEquals(4, restored.getCompletedToolRounds());
        assertEquals(2, restored.getCompletedRecoveryCalls());
        assertEquals(1200, restored.getRecoveredCharacters());
        assertTrue(restored.isSummaryUsed());
    }

    // 引用编号、数据库目标和目标版本一起保存，恢复后 memory_5 仍指向原记录。
    @Test
    void retainsMemoryTargetVersionsAndNeverReusesOldNumbers() {
        AgentRunCheckpoint source = new AgentRunCheckpoint();
        LocalDateTime version = LocalDateTime.of(2026, 9, 27, 12, 0, 0, 123_000_000);
        source.setMemoryTargets(List.of(new MemoryExtractionTarget("memory_5", MemoryScope.USER,
                7L, 42L, "sport", "运动", "篮球", version)));
        source.setNextMemoryNumber(12);
        AgentRunCheckpoint restored = roundTrip(source);
        MemoryReferenceRegistry registry = new MemoryReferenceRegistry();
        try {
            registry.restoreRun(7L, 9L, "修改偏好", "00000000-0000-0000-0000-000000000001",
                    restored.getMemoryTargets(), restored.getNextMemoryNumber());
            assertEquals(42L, registry.resolve("memory_5").getMemoryId());
            assertEquals(version, registry.toolContext().resolve("memory_5").getUpdatedAt());
            assertEquals(12, registry.nextReferenceNumber());
            assertEquals("修改偏好", registry.currentUserMessage());
        } finally {
            registry.clear();
        }
    }

    // 清理过的恢复编号也不能复用，否则模型可能用旧编号读到另一份原文。
    @Test
    void retainsRecoveryMapAndNextNumber() {
        AgentRunCheckpoint source = new AgentRunCheckpoint();
        source.setRecoveryReferences(Map.of("result_2", "original-call"));
        source.setNextRecoveryNumber(8);
        AgentRunCheckpoint restored = roundTrip(source);
        RecoveryReferenceRegistry registry = new RecoveryReferenceRegistry("00000000-0000-0000-0000-000000000001");
        registry.restore(restored.getRecoveryReferences(), restored.getNextRecoveryNumber());
        assertEquals("original-call", registry.resolve("result_2"));
        assertEquals("result_1_8", registry.register("new-call"));
    }

    // 新格式引用经过 JSON 持久化再恢复，仍指向同一目标，并从原计数位置继续编号。
    @Test
    void retainsTaskScopedReferencesAcrossCheckpointRoundTrip() {
        String runId = "12345678-1234-4234-8234-123456789abc";
        MemoryReferenceRegistry sourceMemory = new MemoryReferenceRegistry();
        MemoryReferenceRegistry restoredMemory = new MemoryReferenceRegistry();
        try {
            sourceMemory.beginRun(7L, 9L, "继续学习", runId);
            UserMemory userMemory = new UserMemory();
            userMemory.setId(42L);
            userMemory.setUserId(7L);
            String userRef = sourceMemory.registerUserMemory(userMemory);
            SessionMemory sessionMemory = new SessionMemory();
            sessionMemory.setId(43L);
            sessionMemory.setSessionId(9L);
            String sessionRef = sourceMemory.registerSessionMemory(sessionMemory);
            RecoveryReferenceRegistry sourceRecovery = new RecoveryReferenceRegistry(runId);
            String resultRef = sourceRecovery.register("original-call");

            // 使用真实 JSON 往返，不能靠内存中的同一个 Java 对象假装恢复成功。
            AgentRunCheckpoint source = new AgentRunCheckpoint();
            source.setRunId(runId);
            source.setMemoryTargets(sourceMemory.toolContext().getTargets());
            source.setNextMemoryNumber(sourceMemory.nextReferenceNumber());
            source.setRecoveryReferences(sourceRecovery.snapshot());
            source.setNextRecoveryNumber(sourceRecovery.nextNumber());
            AgentRunCheckpoint restored = roundTrip(source);
            restoredMemory.restoreRun(7L, 9L, "继续学习", restored.getRunId(),
                    restored.getMemoryTargets(), restored.getNextMemoryNumber());
            RecoveryReferenceRegistry restoredRecovery = new RecoveryReferenceRegistry(restored.getRunId());
            restoredRecovery.restore(restored.getRecoveryReferences(), restored.getNextRecoveryNumber());

            // 长期记忆、会话记忆和工具结果都保留暂停前的完整引用。
            assertEquals(42L, restoredMemory.resolve(userRef).getMemoryId());
            assertEquals(43L, restoredMemory.resolve(sessionRef).getMemoryId());
            assertEquals(userRef, restoredMemory.registerUserMemory(userMemory));
            assertEquals("original-call", restoredRecovery.resolve(resultRef));
            assertEquals(resultRef, restoredRecovery.register("original-call"));
            userMemory.setId(44L);
            assertEquals(userRef.substring(0, userRef.lastIndexOf('_') + 1) + "3",
                    restoredMemory.registerUserMemory(userMemory));
            assertEquals(resultRef.substring(0, resultRef.lastIndexOf('_') + 1) + "2",
                    restoredRecovery.register("new-call"));
        } finally {
            // 测试也释放线程局部数据，避免影响同一线程上的后续测试。
            sourceMemory.clear();
            restoredMemory.clear();
        }
    }

    // 暂停前已经完成的记忆写入凭据必须保留，否则后置提取可能重复新增同一事实。
    @Test
    void retainsActualWriteReceiptWithoutExposingItToModel() {
        MemoryWriteReceipt receipt = new MemoryWriteReceipt(MemoryOperation.UPDATE, MemoryScope.USER,
                7L, List.of(42L), List.of("sport"));
        ToolExecutionResult result = ToolExecutionResult.memoryWriteSuccess("已修改", receipt);
        ToolExecutionRecord record = ToolExecutionRecord.requested(1, new ToolCall("a", "update_memory", "{}"))
                .withMemoryWriteTool(true).withApprovalDecision("APPROVED")
                .withOutcome(ToolExecutionRecord.Status.SUCCEEDED, result, null);
        AgentRunCheckpoint source = new AgentRunCheckpoint();
        source.setToolExecutions(List.of(ToolExecutionSnapshot.from(record)));
        ToolExecutionRecord restored = roundTrip(source).getToolExecutions().getFirst().restore();
        assertTrue(restored.isMemoryWriteTool());
        assertEquals("APPROVED", restored.getApprovalDecision());
        assertEquals(List.of(42L), restored.getResult().memoryWriteReceipt().getMemoryIds());
        assertEquals(MemoryOperation.UPDATE, restored.getResult().memoryWriteReceipt().getOperation());
        assertFalse(json.writeValueAsString(restored.getResult()).contains("memoryIds"));
    }

    // 检查点恢复原样保留校验失败和真正拒绝，旧拒绝不会凭 retryable 被放开。
    @Test
    void retainsFailureMeaningAcrossJsonRoundTrip() {
        AgentRunContext run = new AgentRunContext();
        ToolCall invalid = new ToolCall("invalid", "write", "{}");
        run.requestToolExecution(invalid);
        run.failToolValidation(invalid, ToolExecutionResult.failure("INVALID_INPUT", "修正参数", true));
        ToolCall denied = new ToolCall("denied", "write", "{}");
        run.requestToolExecution(denied);
        run.recordToolApproval(denied, "REJECTED");
        run.rejectToolExecution(denied, ToolExecutionResult.failure("DENIED", "不能执行", true));
        AgentRunCheckpoint source = new AgentRunCheckpoint();
        source.setToolExecutions(run.getToolExecutions().stream().map(ToolExecutionSnapshot::from).toList());

        List<ToolExecutionRecord> restored = roundTrip(source).getToolExecutions().stream()
                .map(ToolExecutionSnapshot::restore).toList();
        assertEquals(ToolExecutionRecord.Status.VALIDATION_FAILED, restored.get(0).getStatus());
        assertFalse(restored.get(0).blocksContinuation());
        assertEquals(ToolExecutionRecord.Status.REJECTED, restored.get(1).getStatus());
        assertTrue(restored.get(1).blocksContinuation());
        assertTrue(restored.get(1).getResult().isRetryable());
        assertEquals("REJECTED", restored.get(1).getApprovalDecision());
        assertNull(restored.get(0).getApprovalDecision());
    }

    // 使用生产同款 JSON 转换，不借助对象引用保留数据。
    private AgentRunCheckpoint roundTrip(AgentRunCheckpoint checkpoint) {
        return json.readValue(json.writeValueAsString(checkpoint), AgentRunCheckpoint.class);
    }
}
