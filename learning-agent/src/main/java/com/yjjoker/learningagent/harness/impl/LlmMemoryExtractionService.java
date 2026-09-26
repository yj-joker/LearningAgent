package com.yjjoker.learningagent.harness.impl;

import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.memory.MemoryCandidate;
import com.yjjoker.learningagent.harness.memory.MemoryExtractionService;
import com.yjjoker.learningagent.harness.memory.MemoryExtractionFormatException;
import com.yjjoker.learningagent.harness.memory.MemoryOperation;
import com.yjjoker.learningagent.harness.memory.MemoryScope;
import com.yjjoker.learningagent.config.MemoryExtractionRetryProperties;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

// 使用无工具 LLM 请求提取候选记忆，避免提取阶段再次进入业务工具循环。
@Component
@Slf4j
public class LlmMemoryExtractionService implements MemoryExtractionService {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();
    private static final int MAX_MEMORY_KEY_LENGTH = 128;
    private static final int MAX_MEMORY_TOPIC_LENGTH = 128;
    private static final int MAX_MEMORY_SUMMARY_LENGTH = 1_000;
    private static final int MAX_MEMORY_CONTENT_LENGTH = 20_000;

    private final LlmClient llmClient;
    private final LlmRetryExecutor llmRetryExecutor;
    private final MemoryExtractionRetryProperties retryProperties;

    // Spring 使用这个构造方法注入模型客户端、网络重试器和提取重试配置。
    @Autowired
    public LlmMemoryExtractionService(LlmClient llmClient,
                                      LlmRetryExecutor llmRetryExecutor,
                                      MemoryExtractionRetryProperties retryProperties) {
        this.llmClient = llmClient;
        this.llmRetryExecutor = llmRetryExecutor;
        this.retryProperties = retryProperties;
    }

    // 测试使用默认的格式修复次数，避免测试必须创建 Spring 配置对象。
    public LlmMemoryExtractionService(LlmClient llmClient,
                                      LlmRetryExecutor llmRetryExecutor) {
        this(llmClient, llmRetryExecutor, new MemoryExtractionRetryProperties());
    }

    @Override
    // 从用户问题和助手回答中提取结构化记忆候选。
    public List<MemoryCandidate> extract(Long sessionId,
                                         String userMessage,
                                         String assistantAnswer) {
        if (userMessage == null || userMessage.isBlank()
                || assistantAnswer == null || assistantAnswer.isBlank()) {
            // 缺少完整对话时不调用模型，也不生成不可靠的记忆。
            return List.of();
        }

        // 只把本轮问题和最终回答交给提取模型。
        String extractionInput = "用户问题：\n" + userMessage
                + "\n\n助手最终回答：\n" + assistantAnswer;

        // 格式错误最多修复有限次数，避免提取模型无限循环。
        int maxAttempts = Math.max(1, retryProperties.getMaxAttempts());
        String repairHint = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LlmResponse response = requestExtraction(extractionInput, repairHint);
                String responseText = requireTextResponse(response);

                // 解析并校验模型返回的 JSON。
                List<MemoryCandidate> candidates = parseCandidates(responseText);
                log.info("记忆候选提取完成，sessionId={}，attempt={}，maxAttempts={}，candidateCount={}，responseCharacters={}",
                        sessionId, attempt, maxAttempts, candidates.size(), responseText.length());
                logCandidates(sessionId, candidates);
                return candidates;
            } catch (MemoryExtractionFormatException exception) {
                if (attempt == maxAttempts) {
                    log.warn("记忆候选修复次数已用尽，sessionId={}，attempt={}，maxAttempts={}，reason={}",
                            sessionId, attempt, maxAttempts, exception.getMessage());
                    throw exception;
                }

                // 把简短错误原因带回下一次请求，让模型只修正格式问题。
                repairHint = exception.getMessage();
                log.warn("记忆候选格式错误，将请求模型修复，sessionId={}，attempt={}，nextAttempt={}，reason={}",
                        sessionId, attempt, attempt + 1, repairHint);
            }
        }

        // 循环必然在成功或抛出异常时结束，这里只是防止未来修改循环后静默返回空结果。
        throw new IllegalStateException("记忆提取修复流程异常结束");
    }

    // 请求一次无工具记忆提取，不处理格式错误。
    private LlmResponse requestExtraction(String extractionInput, String repairHint) {
        String requestContent = repairHint == null || repairHint.isBlank()
                ? extractionInput
                : extractionInput
                + "\n\n上一次提取结果校验失败，失败原因："
                + repairHint
                + "。请修正后只返回合法 JSON，不要添加解释文字。";
        return llmRetryExecutor.generateWithoutTools(
                llmClient,
                List.of(
                        LlmMessage.system(AgentSystemPrompt.MEMORY_EXTRACTION_PROMPT),
                        LlmMessage.user(requestContent)
                )
        );
    }

    // 只接受文本回复，工具调用和空回复都交给修复循环处理。
    private String requireTextResponse(LlmResponse response) {
        if (response instanceof ToolCallLlmResponse) {
            throw new MemoryExtractionFormatException("记忆提取结果不能调用工具");
        }
        if (!(response instanceof TextLlmResponse textResponse)
                || textResponse.content() == null
                || textResponse.content().isBlank()) {
            throw new MemoryExtractionFormatException("记忆提取结果必须是非空文本");
        }
        return textResponse.content();
    }

    // 记录候选数量和字段长度，不记录完整记忆正文。
    private void logCandidates(Long sessionId, List<MemoryCandidate> candidates) {
        for (MemoryCandidate candidate : candidates) {
            log.info("记忆候选已识别，sessionId={}，scope={}，operation={}，memoryKey={}，topic={}，summaryCharacters={}，contentCharacters={}",
                    sessionId,
                    candidate.getScope(),
                    candidate.getOperation(),
                    candidate.getMemoryKey(),
                    candidate.getMemoryTopic(),
                    safeLength(candidate.getMemorySummary()),
                    safeLength(candidate.getMemoryContent()));
        }
    }

    // 只接受约定的 JSON 对象，避免把模型的解释文字误当成记忆正文。
    private List<MemoryCandidate> parseCandidates(String responseText) {
        // 解析 memories 数组，并逐条校验候选。
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
            throw new MemoryExtractionFormatException(
                    "记忆提取结果不是有效的候选 JSON：" + exception.getMessage(),
                    exception
            );
        }
    }

    // 逐字段校验候选，保证后续持久化阶段拿到的是完整结构。
    private MemoryCandidate parseCandidate(JsonNode node) {
        // 读取一个候选的作用域、操作和记忆内容。
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
        String operation = requiredText(node, "operation");
        try {
            candidate.setOperation(MemoryOperation.valueOf(operation.toUpperCase()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("operation 必须是 CREATE、UPDATE 或 DELETE");
        }
        candidate.setMemoryKey(requiredText(node, "memoryKey"));
        validateLength("memoryKey", candidate.getMemoryKey(), MAX_MEMORY_KEY_LENGTH);

        // 删除只需要定位目标，避免模型为了凑字段而编造摘要和正文。
        if (candidate.getOperation() == MemoryOperation.DELETE) {
            candidate.setMemoryTopic(optionalText(node, "memoryTopic"));
            candidate.setMemorySummary(optionalText(node, "memorySummary"));
            candidate.setMemoryContent(optionalText(node, "memoryContent"));
            return candidate;
        }

        candidate.setMemoryTopic(requiredText(node, "memoryTopic"));
        candidate.setMemorySummary(requiredText(node, "memorySummary"));
        candidate.setMemoryContent(requiredText(node, "memoryContent"));
        validateLength("memoryTopic", candidate.getMemoryTopic(), MAX_MEMORY_TOPIC_LENGTH);
        validateLength("memorySummary", candidate.getMemorySummary(), MAX_MEMORY_SUMMARY_LENGTH);
        validateLength("memoryContent", candidate.getMemoryContent(), MAX_MEMORY_CONTENT_LENGTH);
        return candidate;
    }

    private String requiredText(JsonNode node, String fieldName) {
        // 读取必填字符串字段。
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.asString().trim();
    }

    private String optionalText(JsonNode node, String fieldName) {
        // 读取删除操作中可以省略的字段。
        JsonNode value = node.get(fieldName);
        return value == null || !value.isTextual() ? "" : value.asString().trim();
    }

    private void validateLength(String fieldName, String value, int maximum) {
        // 限制模型返回字段的长度，避免异常大文本进入数据库。
        if (value.length() > maximum) {
            throw new IllegalArgumentException(fieldName + " 超过长度限制");
        }
    }

    // 允许模型把 JSON 放进 markdown 代码块，但不接受代码块之外的解释文字。
    private String stripMarkdownFence(String responseText) {
        // 去掉 JSON 外层的 markdown 代码块标记。
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

    // 计算字段长度，日志不输出完整正文。
    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
