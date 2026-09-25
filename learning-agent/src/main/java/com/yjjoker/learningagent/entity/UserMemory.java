package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.MemoryStatusEnum;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// 用户长期记忆；通过 userId 关联，可以跨多个学习会话使用。
@Getter
@Setter
public class UserMemory {

    private Long id;
    private Long userId;
    private String memoryKey;
    private String memoryTopic;

    // 索引阶段只读取摘要，减少不必要的上下文和数据库传输。
    private String memorySummary;

    // 只有确认需要该主题时，Harness 才召回完整正文。
    private String memoryContent;

    private MemoryStatusEnum status = MemoryStatusEnum.ACTIVE;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
