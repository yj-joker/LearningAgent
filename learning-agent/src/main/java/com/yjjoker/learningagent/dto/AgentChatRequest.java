package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.yjjoker.learningagent.harness.model.AgentMode;
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

    // 保留字段兼容旧前端；实际模式从后端会话读取，不传或传错都不会改变会话模式。
    // 长期计划关联由会话绑定接口保存，聊天请求不重复设置或清除。
    private AgentMode mode;

}
