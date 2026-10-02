package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

// 独立的无工具审查调用；复用 Aliyun 客户端和网络重试，不新增 AgentLoop。
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmAnswerReviewService implements AnswerReviewService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS = Set.of("action", "reason", "instruction");
    // 输入总量有界；关键状态过大时停止审查，不能悄悄丢掉一半步骤后宣称通过。
    private static final int MAX_INPUT_CHARACTERS = 48_000;
    private static final int MAX_OUTPUT_CHARACTERS = 3_000;
    private final LlmClient llmClient;
    private final LlmRetryExecutor retryExecutor;

    // 每次拟回复只审查一次；网络瞬时错误沿用统一重试，格式错误不再套一层修复循环。
    @Override
    public AnswerReviewResult review(AgentRunContext context, AnswerReviewRequest request) {
        long started = System.nanoTime();
        try {
            if (!context.hasCompleteToolHistory() || request.getProgress() == null) {
                throw new IllegalStateException("审查缺少完整执行事实");
            }
            String input = buildInput(context, request);
            if (input.length() > MAX_INPUT_CHARACTERS) {
                throw new IllegalArgumentException("审查输入超过字符上限");
            }
            log.info("回答审查开始，runId={}，inputCharacters={}，toolCount={}，corrections={}",
                    context.getRunId(), input.length(), context.getToolExecutions().size(),
                    context.getAnswerReviewCorrections());
            var response = retryExecutor.generateWithoutTools("answer-review", llmClient, List.of(
                    LlmMessage.system(AgentSystemPrompt.ANSWER_REVIEW_PROMPT), LlmMessage.user(input)));
            if (!(response instanceof TextLlmResponse text)) {
                throw new IllegalArgumentException("审查只能返回文本 JSON");
            }
            AnswerReviewResult result = parse(text.content());
            log.info("回答审查完成，runId={}，action={}，elapsedMs={}", context.getRunId(),
                    result.getAction(), (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (RuntimeException exception) {
            // 不输出异常正文、用户输入或审查原文，避免日志泄露业务信息。
            log.warn("回答审查未完成，使用保守结果，runId={}，errorType={}，elapsedMs={}",
                    context.getRunId(), exception.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            return AnswerReviewResult.unavailable();
        }
    }

    // 把可信执行状态和不可信自然语言分开标记，审查不能把历史助手说过的话当成写入凭据。
    private String buildInput(AgentRunContext context, AnswerReviewRequest request) {
        ObjectNode root = JSON.createObjectNode();
        root.put("userMessage", request.getUserMessage());
        root.put("draftAnswer", request.getDraftAnswer());
        root.set("databaseProgress", JSON.valueToTree(request.getProgress()));
        root.put("toolHistoryComplete", context.hasCompleteToolHistory());
        appendDialogue(root, request.getDialogue());
        var tools = root.putArray("toolExecutions");
        for (ToolExecutionRecord record : context.getToolExecutions()) {
            ObjectNode item = tools.addObject();
            item.put("sequence", record.getSequence());
            item.put("toolName", record.getToolName());
            item.put("status", record.getStatus().name());
            putExcerpt(item, "arguments", record.getArguments(), 1_200);
            var result = record.getResult();
            if (result != null) {
                item.put("success", result.isSuccess());
                item.put("errorCode", result.getErrorCode());
                item.put("retryable", result.isRetryable());
                putExcerpt(item, "errorMessage", result.getMessage(), 500);
                putExcerpt(item, "result", result.getContent(), 1_500);
                // 记忆写入凭据由后端生成，不用返回文本里的“已保存”代替提交事实。
                item.put("memoryWriteCommitted", result.memoryWriteReceipt() != null);
            }
        }
        return root.toString();
    }

    // 只保留最近的普通对话，帮助理解“继续”等指代；摘要和系统消息不是用户原话。
    private void appendDialogue(ObjectNode root, List<LlmMessage> messages) {
        List<LlmMessage> eligible = messages.stream().filter(message -> !message.isSummary()
                && ("user".equals(message.getRole()) || "assistant".equals(message.getRole()))
                && message.getToolCalls().isEmpty() && message.getContent() != null).toList();
        int start = Math.max(0, eligible.size() - 6);
        root.put("earlierDialogueOmitted", start > 0);
        var dialogue = root.putArray("recentDialogue");
        for (int index = start; index < eligible.size(); index++) {
            LlmMessage message = eligible.get(index);
            ObjectNode item = dialogue.addObject();
            item.put("role", message.getRole());
            putExcerpt(item, "content", message.getContent(), 1_000);
        }
    }

    // 截取大文本时显式标注，缺失部分不能被审查模型当成“没有发生”。
    private void putExcerpt(ObjectNode node, String field, String text, int limit) {
        String value = text == null ? "" : text;
        node.put(field, value.substring(0, Math.min(value.length(), limit)));
        node.put(field + "Truncated", value.length() > limit);
    }

    // 严格校验审查协议；多字段、未知动作或空纠正建议都不能驱动主循环。
    private AnswerReviewResult parse(String content) {
        if (content == null || content.length() > MAX_OUTPUT_CHARACTERS) {
            throw new IllegalArgumentException("审查输出长度不合法");
        }
        JsonNode root = JSON.readTree(content);
        if (!root.isObject() || !root.propertyNames().equals(FIELDS)) {
            throw new IllegalArgumentException("审查字段不完整或含额外字段");
        }
        String actionText = requiredText(root, "action", 30);
        String reason = requiredText(root, "reason", 800);
        String instruction = requiredText(root, "instruction", 1_200);
        AnswerReviewResult.Action action = AnswerReviewResult.Action.valueOf(actionText);
        if (action == AnswerReviewResult.Action.UNAVAILABLE
                || (action != AnswerReviewResult.Action.PASS && instruction.isBlank())) {
            throw new IllegalArgumentException("审查动作或建议不合法");
        }
        return new AnswerReviewResult(action, reason, instruction);
    }

    // 只接收短字符串，拒绝模型把对象或数组塞入解释字段。
    private String requiredText(JsonNode node, String field, int limit) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asString().length() > limit) {
            throw new IllegalArgumentException("审查字段类型或长度不合法");
        }
        return value.asString();
    }
}
