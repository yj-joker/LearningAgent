package com.yjjoker.learningagent.harness.llm.model;

import lombok.Getter;

import java.util.List;

// LlmMessage 是 Harness 内部统一的消息结构，不属于某一家模型厂商。
// 普通消息、模型提出的工具调用、工具执行结果都放进同一个消息列表，按发生顺序组成上下文。
// @Getter 会在编译时为下面四个字段生成 getXxx() 方法，源码中不再重复手写简单读取方法。
@Getter
public class LlmMessage {

    // role 表示消息来源，目前会使用 user、assistant 和 tool 三种值。
    private final String role;

    // content 保存消息正文。assistant 请求工具时没有普通正文，因此这个字段允许为 null。
    private final String content;

    // toolCalls 只在 assistant 请求工具时使用，其他消息会保存为空列表。
    private final List<ToolCall> toolCalls;

    // toolCallId 只在 tool 结果消息中使用，用来关联模型之前提出的某一次工具调用。
    private final String toolCallId;

    private LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this.role = role;
        this.content = content;

        // 普通用户消息没有工具调用，因此允许传入 null，并统一保存成不可修改的空列表。
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.toolCallId = toolCallId;
    }

    // 创建用户消息。role=user 告诉模型这段文字来自最终用户。
    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, List.of(), null);
    }

    // 保存模型刚才返回的工具调用要求。
    // 这一步不能省略，否则下一次请求只有工具结果，模型不知道该结果对应自己提出的哪项调用。
    public static LlmMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new LlmMessage("assistant", null, toolCalls, null);
    }

    // 保存 Java 工具的执行结果。toolCallId 必须与模型原始工具调用中的 id 完全一致。
    // role=tool 表示 content 不是用户说的话，也不是模型回答，而是应用程序提供的外部结果。
    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage("tool", content, List.of(), toolCallId);
    }

}
