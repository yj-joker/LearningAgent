package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.MemoryStatusEnum;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// 会话级结构化记忆；只属于一个学习会话，不替代完整消息历史。
@Getter
@Setter
public class SessionMemory {

    private Long id;
    private Long sessionId;
    private String memoryKey;
    private String memoryTopic;

    // 主题索引使用摘要，正文召回单独执行。
    private String memorySummary;

    // 记录当前会话中的目标、任务状态等结构化事实。
    private String memoryContent;

    private MemoryStatusEnum status = MemoryStatusEnum.ACTIVE;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
