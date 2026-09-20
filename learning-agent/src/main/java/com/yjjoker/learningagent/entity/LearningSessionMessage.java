package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.LearningSessionMessageRoleEnum;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// 一条会话消息，可以表示用户输入、模型回答、工具调用或工具结果。
@Getter
@Setter
public class LearningSessionMessage {

    private Long id;
    private Long sessionId;
    private LearningSessionMessageRoleEnum role;

    // ASSISTANT 请求工具时 content 可以为空，此时调用信息保存在 toolCallsJson。
    private String content;
    private String toolCallsJson;

    // 仅 TOOL 消息使用，用来对应 ASSISTANT 发起的某一次工具调用。
    private String toolCallId;
    private LocalDateTime createdAt;
}
