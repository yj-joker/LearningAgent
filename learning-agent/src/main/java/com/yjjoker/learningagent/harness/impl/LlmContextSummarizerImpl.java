package com.yjjoker.learningagent.harness.impl;

import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.harness.context.ContextSummarizer;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

// 使用同一个 LLM 客户端生成摘要，但通过 generateWithoutTools 禁止摘要阶段调用业务工具。
@Component
@AllArgsConstructor
@Slf4j
public class LlmContextSummarizerImpl implements ContextSummarizer {

    private final LlmClient llmClient;

    @Override
    public String summarize(List<LlmMessage> messages) {
        // 没有可总结的历史时不调用模型，避免产生无意义的额外请求。
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        // 将旧消息整理成一条输入文本，避免摘要请求中出现不完整的工具协议消息。
        LlmMessage history = LlmMessage.user(formatMessages(messages));
        // 摘要请求使用无工具接口，防止摘要模型重新进入工具调用循环。
        LlmResponse response = llmClient.generateWithoutTools(
                List.of(LlmMessage.system(AgentSystemPrompt.SUMMARY_PROMPT), history)
        );

        if (response instanceof TextLlmResponse textResponse
                && textResponse.content() != null
                && !textResponse.content().isBlank()) {
            // 记录摘要结果，测试时可确认摘要确实生成；不记录原始历史输入。
            String summary = textResponse.content().trim();
            log.info("上下文摘要生成完成，输入消息数={}，摘要字符数={}，摘要内容={}",
                    messages.size(), summary.length(), summary);
            return summary;
        }

        // 摘要阶段只接受文本；工具调用说明模型没有遵守摘要职责。
        if (response instanceof ToolCallLlmResponse) {
            throw new LearningAgentServiceException("上下文摘要请求不允许调用工具");
        }
        // 如果没有返回文本，则抛出异常
        throw new LearningAgentServiceException("上下文摘要请求没有返回文本");
    }

    // 将消息列表格式化为单个字符串
    private String formatMessages(List<LlmMessage> messages) {
        StringJoiner joiner = new StringJoiner("\n\n");
        for (LlmMessage message : messages) {
            if (message == null) {
                continue;
            }
            StringBuilder block = new StringBuilder();
            block.append("角色：").append(message.getRole()).append("\n");
            if (!message.getToolCalls().isEmpty()) {
                block.append("工具请求：");
                for (ToolCall toolCall : message.getToolCalls()) {
                    block.append(toolCall.name())
                            .append(" 参数=")
                            .append(toolCall.arguments())
                            .append("；");
                }
                block.append("\n");
            }
            if (message.getToolCallId() != null) {
                block.append("工具结果：\n");
            }
            if (message.getContent() != null) {
                block.append(message.getContent());
            }
            joiner.add(block);
        }
        return joiner.toString();
    }
}
