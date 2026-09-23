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
    private String summaryContent;
    private Long coveredUntilMessageId;
    private LocalDateTime createdAt;
}
