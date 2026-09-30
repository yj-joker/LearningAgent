package com.yjjoker.learningagent.harness.memory.model;

import lombok.Getter;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 同一份快照贯穿提取、修复和保存，避免 memoryRef 在中途指向另一条记忆。
@Getter
public class MemoryExtractionContext {
    private final Long userId;
    private final Long sessionId;
    private final List<MemoryExtractionTarget> targets;
    private final Map<String, MemoryExtractionTarget> targetsByRef;
    // 保留本轮执行快照，提取模型只会收到经过筛选的字段，不直接序列化这些对象。
    private final List<ToolExecutionRecord> toolExecutions;
    // 全部记录用于排查，只把显式记忆写操作交给提取模型。
    private final List<ToolExecutionRecord> memoryWriteExecutions;

    // 给本次索引建立独立映射；不复用历史对话中的引用。
    public MemoryExtractionContext(Long userId, Long sessionId, MemoryIndexSnapshot index) {
        this(userId, sessionId, index, List.of());
    }

    // 提取和有限修复共用同一份最新索引及工具轨迹，不在重试期间重新编号或追加结果。
    public MemoryExtractionContext(Long userId, Long sessionId, MemoryIndexSnapshot index,
                                   List<ToolExecutionRecord> toolExecutions) {
        if (userId == null || userId <= 0 || sessionId == null || sessionId <= 0) {
            throw new IllegalArgumentException("记忆提取的用户和会话 ID 必须大于 0");
        }
        this.userId = userId;
        this.sessionId = sessionId;
        // 未完成的调用不能被误解为失败或没有发生，拒绝不完整记录。
        this.toolExecutions = List.copyOf(toolExecutions);
        if (this.toolExecutions.stream().anyMatch(record -> !record.isFinished())) {
            throw new IllegalStateException("工具执行轨迹尚未完成，不能开始自动提取");
        }
        this.memoryWriteExecutions = this.toolExecutions.stream()
                .filter(ToolExecutionRecord::isMemoryWriteTool).toList();
        // 在生成模型输入前核对归属，避免把其他用户的目标或结果泄露给模型。
        validateWriteReceipts();
        List<MemoryExtractionTarget> entries = new ArrayList<>();
        // 两类记忆统一编号，但各自保留归属范围。
        index.getUserMemories().forEach(memory -> {
            requireOwner(userId, memory.getUserId(), memory.getId());
            entries.add(new MemoryExtractionTarget("memory_" + (entries.size() + 1),
                    MemoryScope.USER, userId, memory.getId(), memory.getMemoryKey(),
                    memory.getMemoryTopic(), memory.getMemorySummary(), memory.getUpdatedAt()));
        });
        index.getSessionMemories().forEach(memory -> {
            requireOwner(sessionId, memory.getSessionId(), memory.getId());
            entries.add(new MemoryExtractionTarget("memory_" + (entries.size() + 1),
                    MemoryScope.SESSION, sessionId, memory.getId(), memory.getMemoryKey(),
                    memory.getMemoryTopic(), memory.getMemorySummary(), memory.getUpdatedAt()));
        });
        // 复制字段和集合，让外部列表或实体的变化不影响本次映射。
        this.targets = List.copyOf(entries);
        Map<String, MemoryExtractionTarget> references = new LinkedHashMap<>();
        entries.forEach(entry -> references.put(entry.getMemoryRef(), entry));
        this.targetsByRef = Map.copyOf(references);
    }

    // 主模型工具使用已展示的引用快照，不重新编号，也不套用后置提取的重复写入限制。
    public MemoryExtractionContext(Long userId, Long sessionId, List<MemoryExtractionTarget> targets) {
        if (userId == null || userId <= 0 || sessionId == null || sessionId <= 0) {
            throw new IllegalArgumentException("记忆操作缺少当前用户或会话");
        }
        this.userId = userId;
        this.sessionId = sessionId;
        this.toolExecutions = List.of();
        this.memoryWriteExecutions = List.of();
        this.targets = List.copyOf(targets);
        Map<String, MemoryExtractionTarget> references = new LinkedHashMap<>();
        for (MemoryExtractionTarget target : targets) {
            // 引用只能指向当前用户或会话；重复引用会让目标不明确。
            if (target.getScope() == null || target.getMemoryRef() == null || target.getMemoryRef().isBlank()) {
                throw new IllegalArgumentException("记忆引用不完整");
            }
            requireOwner(target.getScope() == MemoryScope.USER ? userId : sessionId,
                    target.getOwnerId(), target.getMemoryId());
            if (references.putIfAbsent(target.getMemoryRef(), target) != null) {
                throw new IllegalArgumentException("记忆引用重复");
            }
        }
        this.targetsByRef = Map.copyOf(references);
    }

    // 查找本次提供过的引用，未知引用返回 null。
    public MemoryExtractionTarget resolve(String memoryRef) {
        return memoryRef == null ? null : targetsByRef.get(memoryRef);
    }

    // 拒绝、失败或缺少成功凭据时，无法确认可补写的目标；本轮不允许自动补做任何写入。
    public boolean hasUnconfirmedMemoryWrites() {
        return memoryWriteExecutions.stream().anyMatch(record ->
                record.getStatus() != ToolExecutionRecord.Status.SUCCEEDED
                        || record.getResult() == null || record.getResult().memoryWriteReceipt() == null);
    }

    // 已发生写入的范围暂不自动新增，防止删除后换一个同义 key 又被提取回来。
    // 这是保守限制，不是假装后端能够准确识别任意两段文本是否同义。
    public List<MemoryScope> getBlockedCreateScopes() {
        return confirmedWrites().stream().map(MemoryWriteReceipt::getScope).distinct().toList();
    }

    // 只比较同一范围内的真实 ID 或 key；不同表可以有相同 ID，不能混在一起判断。
    public boolean isProtectedMemoryTarget(MemoryExtractionTarget target) {
        return confirmedWrites().stream().anyMatch(receipt -> receipt.getScope() == target.getScope()
                && Objects.equals(receipt.getOwnerId(), target.getOwnerId())
                && (receipt.getMemoryIds().contains(target.getMemoryId())
                    || receipt.getMemoryKeys().contains(target.getMemoryKey())));
    }

    // 给提取模型重新映射当前引用，不发送数据库 ID，也不复用工具请求中的旧 memoryRef。
    public List<String> currentRefsFor(MemoryWriteReceipt receipt) {
        return targets.stream().filter(target -> target.getScope() == receipt.getScope()
                && Objects.equals(target.getOwnerId(), receipt.getOwnerId())
                && receipt.getMemoryIds().contains(target.getMemoryId()))
                .map(MemoryExtractionTarget::getMemoryRef).toList();
    }

    // 只有成功且携带凭据的结果才用于目标保护；失败结果不会被当成已提交的写入。
    private List<MemoryWriteReceipt> confirmedWrites() {
        return memoryWriteExecutions.stream()
                .filter(record -> record.getStatus() == ToolExecutionRecord.Status.SUCCEEDED && record.getResult() != null)
                .map(record -> record.getResult().memoryWriteReceipt()).filter(Objects::nonNull).toList();
    }

    // 凭据是后端对象，但仍需检查工具声明、执行状态和用户/会话归属是否一致。
    private void validateWriteReceipts() {
        for (ToolExecutionRecord record : toolExecutions) {
            MemoryWriteReceipt receipt = record.getResult() == null ? null : record.getResult().memoryWriteReceipt();
            if (receipt == null) {
                continue;
            }
            if (!record.isMemoryWriteTool() || record.getStatus() != ToolExecutionRecord.Status.SUCCEEDED
                    || !record.getResult().isSuccess()) {
                throw new IllegalStateException("记忆写入凭据与工具声明或状态不一致");
            }
            Long expectedOwner = receipt.getScope() == MemoryScope.USER ? userId : sessionId;
            if (!Objects.equals(expectedOwner, receipt.getOwnerId())) {
                throw new IllegalStateException("记忆写入凭据不属于当前用户或会话");
            }
        }
    }

    // 拒绝把其他用户或会话的记忆加入本次索引。
    private void requireOwner(Long expectedOwner, Long actualOwner, Long memoryId) {
        if (!Objects.equals(expectedOwner, actualOwner) || memoryId == null || memoryId <= 0) {
            throw new IllegalArgumentException("记忆索引归属或 ID 不合法");
        }
    }
}
