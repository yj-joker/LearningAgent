package com.yjjoker.learningagent.harness.impl;

import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.memory.MemoryCandidate;
import com.yjjoker.learningagent.harness.memory.MemoryExtractionService;
import com.yjjoker.learningagent.harness.memory.MemoryScope;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

// 使用无工具 LLM 请求提取候选记忆，避免提取阶段再次进入业务工具循环。
@Component
@AllArgsConstructor
@Slf4j
public class LlmMemoryExtractionService implements MemoryExtractionService {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();
    private static final int MAX_MEMORY_KEY_LENGTH = 128;
    private static final int MAX_MEMORY_TOPIC_LENGTH = 128;
    private static final int MAX_MEMORY_SUMMARY_LENGTH = 1_000;
    private static final int MAX_MEMORY_CONTENT_LENGTH = 20_000;

    private final LlmClient llmClient;
    private final LlmRetryExecutor llmRetryExecutor;

    @Override
    public List<MemoryCandidate> extract(Long sessionId,
                                         String userMessage,
                                         String assistantAnswer) {
        if (userMessage == null || userMessage.isBlank()
                || assistantAnswer == null || assistantAnswer.isBlank()) {
            // 缺少完整对话时不调用模型，也不生成不可靠的记忆。
            return List.of();
        }

        String extractionInput = "用户问题：\n" + userMessage
                + "\n\n助手最终回答：\n" + assistantAnswer;
        LlmResponse response = llmRetryExecutor.generateWithoutTools(
                llmClient,
                List.of(
                        LlmMessage.system(AgentSystemPrompt.MEMORY_EXTRACTION_PROMPT),
                        LlmMessage.user(extractionInput)
                )
        );

        if (response instanceof ToolCallLlmResponse) {
            // 提取阶段不允许工具调用，防止候选提取再次改变主 Agent 流程。
            throw new IllegalStateException("记忆提取请求不允许调用工具");
        }
        if (!(response instanceof TextLlmResponse textResponse)
                || textResponse.content() == null
                || textResponse.content().isBlank()) {
            throw new IllegalStateException("记忆提取请求没有返回文本");
        }

        List<MemoryCandidate> candidates = parseCandidates(textResponse.content());
        log.info("记忆候选提取完成，sessionId={}，candidateCount={}，responseCharacters={}",
                sessionId, candidates.size(), textResponse.content().length());
        for (MemoryCandidate candidate : candidates) {
            // 只记录候选规模和定位字段，不把完整记忆正文写入日志。
            log.info("记忆候选已识别，sessionId={}，scope={}，memoryKey={}，topic={}，summaryCharacters={}，contentCharacters={}",
                    sessionId,
                    candidate.getScope(),
                    candidate.getMemoryKey(),
                    candidate.getMemoryTopic(),
                    candidate.getMemorySummary().length(),
                    candidate.getMemoryContent().length());
        }
        return candidates;
    }

    // 只接受约定的 JSON 对象，避免把模型的解释文字误当成记忆正文。
    private List<MemoryCandidate> parseCandidates(String responseText) {
        try {
            JsonNode root = JSON_MAPPER.readTree(stripMarkdownFence(responseText));
            JsonNode memoriesNode = root == null ? null : root.get("memories");
            if (memoriesNode == null || !memoriesNode.isArray()) {
                throw new IllegalArgumentException("记忆提取结果缺少 memories 数组");
            }

            List<MemoryCandidate> candidates = new ArrayList<>();
            for (JsonNode node : memoriesNode) {
                candidates.add(parseCandidate(node));
            }
            return List.copyOf(candidates);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("记忆候选解析失败，responseCharacters={}，reason={}",
                    responseText.length(), exception.getMessage());
            throw new IllegalStateException("记忆提取结果不是有效的候选 JSON", exception);
        }
    }

    // 逐字段校验候选，保证后续持久化阶段拿到的是完整结构。
    private MemoryCandidate parseCandidate(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("记忆候选必须是 JSON 对象");
        }

        MemoryCandidate candidate = new MemoryCandidate();
        String scope = requiredText(node, "scope");
        try {
            candidate.setScope(MemoryScope.valueOf(scope.toUpperCase()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("scope 必须是 USER 或 SESSION");
        }
        candidate.setMemoryKey(requiredText(node, "memoryKey"));
        candidate.setMemoryTopic(requiredText(node, "memoryTopic"));
        candidate.setMemorySummary(requiredText(node, "memorySummary"));
        candidate.setMemoryContent(requiredText(node, "memoryContent"));

        validateLength("memoryKey", candidate.getMemoryKey(), MAX_MEMORY_KEY_LENGTH);
        validateLength("memoryTopic", candidate.getMemoryTopic(), MAX_MEMORY_TOPIC_LENGTH);
        validateLength("memorySummary", candidate.getMemorySummary(), MAX_MEMORY_SUMMARY_LENGTH);
        validateLength("memoryContent", candidate.getMemoryContent(), MAX_MEMORY_CONTENT_LENGTH);
        return candidate;
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.asString().trim();
    }

    private void validateLength(String fieldName, String value, int maximum) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(fieldName + " 超过长度限制");
        }
    }

    // 允许模型把 JSON 放进 markdown 代码块，但不接受代码块之外的解释文字。
    private String stripMarkdownFence(String responseText) {
        String text = responseText.trim();
        if (!text.startsWith("```") || !text.endsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        return text.substring(firstLineEnd + 1, text.length() - 3).trim();
    }
}
