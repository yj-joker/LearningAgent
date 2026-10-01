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

    // 不传时保持问答模式，兼容已有客户端请求。
    // TODO 专注模式允许选择学习计划，也允许不关联；后续再增加计划选择字段及归属校验。
    // TODO 先明确关联是在会话还是目标上保存，后续请求未传选择字段不能误清除原有关联。
    private AgentMode mode = AgentMode.CHAT;
}
