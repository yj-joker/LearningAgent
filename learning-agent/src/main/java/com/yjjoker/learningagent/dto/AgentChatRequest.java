package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// 智能体聊天请求：会话 ID 定位历史，用户消息表示本轮问题。
@Getter
@Setter
public class AgentChatRequest {

    @NotNull(message = "学习会话 ID 不能为空")
    @Positive(message = "学习会话 ID 必须大于 0")
    private Long sessionId;

    @NotBlank(message = "用户消息不能为空")
    @Size(max = 10_000, message = "用户消息不能超过 10000 个字符")
    private String userMessage;
}
