package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorCode;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.plan.model.GoalIntent;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// 使用无工具 LLM 单独判断用户意图，避免主 Agent 用自然语言猜测是否允许修改状态。
@Service
@Slf4j
public class LlmGoalIntentRecognitionService implements GoalIntentRecognitionService {
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_RESPONSE_CHARACTERS = 2_000;
    private static final Set<String> FIELDS = Set.of(
            "progressReadOnly", "progressMutation", "planMutation", "confidence", "reason");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final LlmClient llmClient;
    private final LlmRetryExecutor retryExecutor;

    // 复用统一 LLM 客户端，但明确使用不带业务工具的调用。
    // TODO 后续可为意图识别配置独立模型名或小模型，当前先保持同一 Aliyun 配置便于学习和复用。
    public LlmGoalIntentRecognitionService(LlmClient llmClient, LlmRetryExecutor retryExecutor) {
        this.llmClient = llmClient;
        this.retryExecutor = retryExecutor;
    }

    // 每个 AgentLoop 只识别一次；格式错误最多修复一次，避免额外模型调用失控。
    @Override
    public GoalIntent recognize(String runId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return GoalIntent.unknown();
        }

        List<LlmMessage> messages = new ArrayList<>(List.of(
                LlmMessage.system(AgentSystemPrompt.GOAL_INTENT_PROMPT),
                LlmMessage.user(userMessage)
        ));
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                LlmResponse response = retryExecutor.generateWithoutTools(
                        "goal-intent", llmClient, messages);
                if (!(response instanceof TextLlmResponse text)
                        || text.content() == null
                        || text.content().length() > MAX_RESPONSE_CHARACTERS) {
                    throw invalid("意图识别模型必须返回短 JSON 文本");
                }
                GoalIntent intent = parse(text.content());
                log.info("意图识别完成，runId={}，attempt={}，progressReadOnly={}，progressMutation={}，planMutation={}，confidence={}",
                        runId, attempt, intent.isProgressReadOnly(), intent.isProgressMutation(),
                        intent.isPlanMutation(), intent.getConfidence());
                return intent;
            } catch (HarnessException exception) {
                log.warn("意图识别格式校验失败，runId={}，attempt={}，maxAttempts={}，errorCode={}",
                        runId, attempt, MAX_ATTEMPTS, exception.getErrorCode());
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("意图识别失败，使用保守快照，runId={}", runId);
                    return GoalIntent.unknown();
                }
                // 只追加固定修复要求，不回显模型原文，避免把模型输出当成新的系统指令。
                messages.add(LlmMessage.user("上次意图结果未通过后端 JSON 校验。请只返回完整的五字段 JSON，不能使用 Markdown 或额外文字。"));
            }
        }
        return GoalIntent.unknown();
    }

    // 严格读取五个字段，拒绝 Markdown、尾随文本和模型擅自增加的协议字段。
    private GoalIntent parse(String content) {
        try {
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject() || !root.propertyNames().equals(FIELDS)) {
                throw invalid("意图识别 JSON 字段不完整或包含额外字段");
            }
            boolean readOnly = requiredBoolean(root, "progressReadOnly");
            boolean progressMutation = requiredBoolean(root, "progressMutation");
            boolean planMutation = requiredBoolean(root, "planMutation");
            JsonNode confidenceNode = root.get("confidence");
            if (confidenceNode == null || !confidenceNode.isNumber()) {
                throw invalid("confidence 必须是数字");
            }
            double confidence = confidenceNode.asDouble();
            if (Double.isNaN(confidence) || Double.isInfinite(confidence)
                    || confidence < 0.0 || confidence > 1.0) {
                throw invalid("confidence 必须在 0 到 1 之间");
            }
            JsonNode reasonNode = root.get("reason");
            if (reasonNode == null || !reasonNode.isTextual() || reasonNode.asString().length() > 500) {
                throw invalid("reason 必须是长度不超过 500 的字符串");
            }
            // 查询和写入是互斥语义；同时出现时交给后端按写入保护处理。
            return new GoalIntent(readOnly, progressMutation, planMutation, confidence, reasonNode.asString());
        } catch (JacksonException exception) {
            throw invalid("意图识别结果不是合法 JSON");
        }
    }

    // 读取布尔字段，拒绝模型用字符串 true/false 冒充布尔值。
    private boolean requiredBoolean(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid(field + " 必须是布尔值");
        }
        return value.asBoolean();
    }

    // 把识别格式错误转换成统一的不可重试业务错误，不把原始模型输出写入日志。
    private HarnessException invalid(String message) {
        return new HarnessException(HarnessError.of(
                HarnessErrorCode.LLM_INVALID_RESPONSE,
                message,
                false,
                HarnessErrorSource.LLM
        ));
    }
}
