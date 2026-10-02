package com.yjjoker.learningagent.harness.approval;

import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import com.yjjoker.learningagent.harness.memory.model.*;
import lombok.Data;
import java.util.List;

// 内部检查点可以保存写入凭据；发给模型的 ToolExecutionResult 不暴露这些数据库信息。
@Data
public class ToolExecutionSnapshot {
    private int sequence;
    private ToolCall call;
    private boolean memoryWrite;
    // 与执行状态一起恢复；旧检查点缺少此字段时保持未知，不补造审批事实。
    private String approvalDecision;
    private ToolExecutionRecord.Status status;
    private String failureType;
    private boolean hasResult;
    private boolean success;
    private String content;
    private String errorCode;
    private String errorMessage;
    private boolean retryable;
    private HarnessErrorSource errorSource;
    private MemoryOperation operation;
    private MemoryScope scope;
    private Long ownerId;
    private List<Long> memoryIds;
    private List<String> memoryKeys;

    // 从实际记录生成快照，只保存已知结果，不让模型生成执行凭据。
    public static ToolExecutionSnapshot from(ToolExecutionRecord record) {
        ToolExecutionSnapshot snapshot = new ToolExecutionSnapshot();
        snapshot.sequence = record.getSequence();
        snapshot.call = new ToolCall(record.getToolCallId(), record.getToolName(), record.getArguments());
        snapshot.memoryWrite = record.isMemoryWriteTool();
        snapshot.approvalDecision = record.getApprovalDecision();
        // 原样保存校验失败与真正拒绝，暂停后恢复不能把它们重新混为一类。
        snapshot.status = record.getStatus();
        snapshot.failureType = record.getFailureType();
        ToolExecutionResult result = record.getResult();
        if (result != null) {
            snapshot.hasResult = true;
            snapshot.success = result.isSuccess();
            snapshot.content = result.getContent();
            // 失败信息和成功凭据分开保存，避免把审批中误还原为写入成功。
            if (result.error() != null) {
                snapshot.errorCode = result.getErrorCode();
                snapshot.errorMessage = result.getMessage();
                snapshot.retryable = result.isRetryable();
                snapshot.errorSource = result.error().getSource();
            }
            MemoryWriteReceipt receipt = result.memoryWriteReceipt();
            if (receipt != null) {
                snapshot.operation = receipt.getOperation();
                snapshot.scope = receipt.getScope();
                snapshot.ownerId = receipt.getOwnerId();
                snapshot.memoryIds = receipt.getMemoryIds();
                snapshot.memoryKeys = receipt.getMemoryKeys();
            }
        }
        return snapshot;
    }

    // 在恢复线程中重建后端记录，让自动提取看到暂停前已经完成的记忆操作。
    public ToolExecutionRecord restore() {
        ToolExecutionResult result = null;
        if (hasResult) {
            if (!success) {
                result = ToolExecutionResult.failure(HarnessError.of(errorCode, errorMessage, retryable, errorSource));
            } else if (operation != null) {
                result = ToolExecutionResult.memoryWriteSuccess(content,
                        new MemoryWriteReceipt(operation, scope, ownerId, memoryIds, memoryKeys));
            } else {
                result = ToolExecutionResult.success(content);
            }
        }
        // 旧记录仍保留原来的 REJECTED；不凭 retryable 字段把历史权限拒绝升级为可重试。
        return ToolExecutionRecord.requested(sequence, call).withMemoryWriteTool(memoryWrite)
                .withApprovalDecision(approvalDecision)
                .withOutcome(status, result, failureType);
    }
}
