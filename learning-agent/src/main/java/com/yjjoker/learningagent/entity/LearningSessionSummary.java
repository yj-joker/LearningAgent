package com.yjjoker.learningagent.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// 一条会话摘要，记录正文以及它覆盖到哪条原始消息。
@Getter
@Setter
public class LearningSessionSummary {

    private Long id;
    private Long sessionId;
    // 摘要只覆盖同一模式的历史，防止跨模式重复带入。
    private String agentMode;
    private String summaryContent;
    private Long coveredUntilMessageId;
    private LocalDateTime createdAt;
}
