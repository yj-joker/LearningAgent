package com.yjjoker.learningagent.harness.memory.model;

import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

// 保存提取时的一条索引快照；数据库 ID 和归属只供后端使用。
@Getter
public class MemoryExtractionTarget {
    // 模型只能用本次索引提供的短引用选择目标。
    private final String memoryRef;
    private final MemoryScope scope;
    private final Long ownerId;
    private final Long memoryId;
    // 保存原 key 和索引内容，写入前检查记忆是否已经改变。
    private final String memoryKey;
    private final String memoryTopic;
    private final String memorySummary;
    private final LocalDateTime updatedAt;

    // 从数据库检查点恢复旧版本，不能用审批期间变化后的索引替换这个快照。
    @JsonCreator
    public MemoryExtractionTarget(@JsonProperty("memoryRef") String memoryRef,
            @JsonProperty("scope") MemoryScope scope, @JsonProperty("ownerId") Long ownerId,
            @JsonProperty("memoryId") Long memoryId, @JsonProperty("memoryKey") String memoryKey,
            @JsonProperty("memoryTopic") String memoryTopic, @JsonProperty("memorySummary") String memorySummary,
            @JsonProperty("updatedAt") LocalDateTime updatedAt) {
        this.memoryRef = memoryRef;
        this.scope = scope;
        this.ownerId = ownerId;
        this.memoryId = memoryId;
        this.memoryKey = memoryKey;
        this.memoryTopic = memoryTopic;
        this.memorySummary = memorySummary;
        this.updatedAt = updatedAt;
    }
}
