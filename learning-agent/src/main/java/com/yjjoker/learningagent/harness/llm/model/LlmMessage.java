package com.yjjoker.learningagent.harness.llm.model;

import lombok.Getter;

import java.util.List;

// LlmMessage 是 Harness 内部统一的消息结构，不属于某一家模型厂商。
// 普通消息、模型提出的工具调用、工具执行结果都放进同一个消息列表，按发生顺序组成上下文。
// @Getter 会在编译时为下面四个字段生成 getXxx() 方法，源码中不再重复手写简单读取方法。
@Getter
public class LlmMessage {

    // role 表示消息来源，目前会使用 system、user、assistant 和 tool 四种值。
    private final String role;

    // originalContent 保存完整正文。工具结果被压缩时，这份原文仍用于持久化和后续恢复。
    private final String originalContent;

    // contextContent 是发送给模型的压缩副本；null 表示直接使用 originalContent。
    private final String contextContent;

    // toolCalls 只在 assistant 请求工具时使用，其他消息会保存为空列表。
    private final List<ToolCall> toolCalls;

    // toolCallId 只在 tool 结果消息中使用，用来关联模型之前提出的某一次工具调用。
    private final String toolCallId;

    // true 表示后续请求可以从数据库重新加载；false 表示只在当前 Agent Loop 使用。
    private final boolean contextReplayable;

    // 只有上下文摘要消息为 true，避免通过正文前缀猜测消息类型。
    private final boolean summary;

    private LlmMessage(String role,
                       String originalContent,
                       String contextContent,
                       List<ToolCall> toolCalls,
                       String toolCallId,
                       boolean contextReplayable,
                       boolean summary) {
        this.role = role;
        this.originalContent = originalContent;
        this.contextContent = contextContent;

        // 普通用户消息没有工具调用，因此允许传入 null，并统一保存成不可修改的空列表。
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.toolCallId = toolCallId;
        this.contextReplayable = contextReplayable;
        this.summary = summary;
    }

    // system 消息保存智能体的统一规则，必须放在用户消息之前发送给模型。
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, List.of(), null, true, false);
    }

    // 创建用户消息。role=user 告诉模型这段文字来自最终用户。
    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, List.of(), null, true, false);
    }

    // 创建模型的普通文本回答，与包含工具调用的 assistant 消息区分开。
    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content, null, List.of(), null, true, false);
    }

    // 创建历史摘要消息；摘要身份由字段标记，不依赖正文格式。
    public static LlmMessage summary(String content) {
        return new LlmMessage("assistant", content, null, List.of(), null, true, true);
    }

    // 保存模型刚才返回的工具调用要求。
    // 这一步不能省略，否则下一次请求只有工具结果，模型不知道该结果对应自己提出的哪项调用。
    public static LlmMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return assistantToolCalls(toolCalls, true);
    }

    // 恢复工具调用使用 contextReplayable=false：当前循环可见，但后续加载历史时会被过滤。
    public static LlmMessage assistantToolCalls(List<ToolCall> toolCalls, boolean contextReplayable) {
        return new LlmMessage("assistant", null, null, toolCalls, null, contextReplayable, false);
    }

    // 保存 Java 工具的执行结果。toolCallId 必须与模型原始工具调用中的 id 完全一致。
    // role=tool 表示 content 不是用户说的话，也不是模型回答，而是应用程序提供的外部结果。
    public static LlmMessage toolResult(String toolCallId, String content) {
        return toolResult(toolCallId, content, true);
    }

    // contextReplayable 与对应的 assistant 工具请求保持一致，避免未来上下文只剩半组工具消息。
    public static LlmMessage toolResult(String toolCallId, String content, boolean contextReplayable) {
        return new LlmMessage("tool", content, null, List.of(), toolCallId, contextReplayable, false);
    }

    // 从数据库恢复工具消息时，同时带回完整原文和已经持久化的上下文副本。
    public static LlmMessage toolResultWithContextContent(String toolCallId,
                                                          String originalContent,
                                                          String contextContent,
                                                          boolean contextReplayable) {
        return new LlmMessage(
                "tool",
                originalContent,
                contextContent,
                List.of(),
                toolCallId,
                contextReplayable,
                false
        );
    }

    // LLM 只读取上下文副本；没有副本时才读取完整正文。
    public String getContent() {
        return contextContent == null ? originalContent : contextContent;
    }

    // 压缩产生新消息对象，原消息仍保留完整正文，便于持久化时分别保存两份内容。
    public LlmMessage withContextContent(String compactedContextContent) {
        if (!"tool".equals(role)) {
            throw new IllegalStateException("只有工具结果消息可以设置上下文副本");
        }
        return new LlmMessage(
                role,
                originalContent,
                compactedContextContent,
                toolCalls,
                toolCallId,
                contextReplayable,
                summary
        );
    }

}
