package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.config.HarnessAnswerReviewProperties;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

// 独立的无工具审查调用；复用 Aliyun 客户端和网络重试，不新增 AgentLoop。
@Service
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
    private final HarnessAnswerReviewProperties properties;

    // Spring 使用配置构造器，生产环境可以调整审查格式修复次数。
    @Autowired
    public LlmAnswerReviewService(LlmClient llmClient, LlmRetryExecutor retryExecutor,
                                  HarnessAnswerReviewProperties properties) {
        this.llmClient = llmClient;
        this.retryExecutor = retryExecutor;
        this.properties = properties;
    }

    // 单元测试和旧调用方使用默认的两次审查预算。
    public LlmAnswerReviewService(LlmClient llmClient, LlmRetryExecutor retryExecutor) {
        this(llmClient, retryExecutor, new HarnessAnswerReviewProperties());
    }

    // 每次拟回复只审查一次；格式错误只允许有限修复，网络错误沿用统一网络重试。
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
            log.info("回答审查开始，runId={}，inputCharacters={}，toolCount={}，corrections={}，maxAttempts={}",
                    context.getRunId(), input.length(), context.getToolExecutions().size(),
                    context.getAnswerReviewCorrections(), reviewAttempts());
            ReviewFormatException lastFormatException = null;
            for (int attempt = 1; attempt <= reviewAttempts(); attempt++) {
                try {
                    // 第二次请求只增加格式修复说明，不把原始模型输出再次拼回上下文。
                    String requestInput = attempt == 1 ? input : appendFormatRepairInstruction(input);
                    var response = retryExecutor.generateWithoutTools("answer-review", llmClient, List.of(
                            LlmMessage.system(AgentSystemPrompt.ANSWER_REVIEW_PROMPT),
                            LlmMessage.user(requestInput)));
                    if (!(response instanceof TextLlmResponse text)) {
                        throw new ReviewFormatException("审查只能返回文本 JSON");
                    }
                    AnswerReviewResult result = parse(text.content());
                    log.info("回答审查完成，runId={}，action={}，attempt={}，elapsedMs={}", context.getRunId(),
                            result.getAction(), attempt, (System.nanoTime() - started) / 1_000_000);
                    return result;
                } catch (ReviewFormatException exception) {
                    lastFormatException = exception;
                    log.warn("回答审查格式无效，runId={}，attempt={}，maxAttempts={}，errorType={}",
                            context.getRunId(), attempt, reviewAttempts(), exception.getClass().getSimpleName());
                }
            }
            throw lastFormatException == null
                    ? new ReviewFormatException("审查格式修复未完成") : lastFormatException;
        } catch (RuntimeException exception) {
            // 不输出异常正文、用户输入或审查原文，避免日志泄露业务信息。
            log.warn("回答审查未完成，使用保守结果，runId={}，errorType={}，elapsedMs={}",
                    context.getRunId(), exception.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            return AnswerReviewResult.unavailable();
        }
    }

    // 防御非法配置，确保格式错误至少有一次请求机会。
    private int reviewAttempts() {
        return Math.max(1, properties.getMaxAttempts());
    }

    // 告诉审查模型只修复协议，不允许改变后端事实或执行任何工具。
    private String appendFormatRepairInstruction(String input) {
        return input + "\n\n【协议修复】上一次审查输出无法解析。请严格只返回完整 JSON，字段必须为 action、reason、instruction，"
                + "不要输出 Markdown、解释文字或额外字段；不要改变执行事实。";
    }

    // 把可信执行状态和不可信自然语言分开标记，审查不能把历史助手说过的话当成写入凭据。
    private String buildInput(AgentRunContext context, AnswerReviewRequest request) {
        ObjectNode root = JSON.createObjectNode();
        // 审批等待分支已提前返回，不会进入最终回答审查；原始问题不包含之后的页面操作。
        root.put("reviewPhase", "BEFORE_FINAL_ANSWER");
        root.put("waitingForToolApproval", false);
        root.put("originalUserRequest", request.getUserMessage());
        root.put("toolHistoryComplete", context.hasCompleteToolHistory());
        // 后端决定是否允许补调用；审查只能建议动作，不能覆盖真实拒绝。
        root.put("continuationAllowed", !context.hasBlockingToolOutcome());
        appendLearningReviewFacts(root, context, request);
        var approvals = root.putArray("userApprovalEvents");
        var tools = root.putArray("toolExecutions");
        for (ToolExecutionRecord record : context.getToolExecutions()) {
            // 页面上的批准/拒绝也是用户行动，单独列出，不能被早先“稍后确认”的文字覆盖。
            if (record.getApprovalDecision() != null) {
                ObjectNode event = approvals.addObject();
                event.put("sequence", record.getSequence());
                event.put("toolCallId", record.getToolCallId());
                event.put("actor", "USER");
                event.put("decision", record.getApprovalDecision());
            }
            ObjectNode item = tools.addObject();
            item.put("sequence", record.getSequence());
            item.put("toolCallId", record.getToolCallId());
            item.put("toolName", record.getToolName());
            item.put("status", record.getStatus().name());
            // 用户原话可能仍写着“稍后确认”；后端审批决定才表示确认是否已经发生。
            item.put("approvalDecision", record.getApprovalDecision());
            // 每次尝试分别展示，避免只见到同名工具的一次失败就否定后一次成功。
            item.put("blocksContinuation", record.blocksContinuation());
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
        // 最后给出执行后的当前状态和待核对草稿，不让输入参数的旧版本冒充最新状态。
        root.set("databaseProgress", progressFacts(request.getProgress()));
        appendDialogue(root, request.getDialogue());
        root.put("draftAnswer", request.getDraftAnswer());
        return root.toString();
    }

    // 把学习阶段的后端事实单独交给审查模型，避免它把短期任务步骤当成长阶段状态。
    private void appendLearningReviewFacts(ObjectNode root, AgentRunContext context,
                                           AnswerReviewRequest request) {
        var progress = request.getLearningPlanProgress();
        boolean active = progress != null && request.getLearningPlanStageRef() != null;
        ObjectNode learning = root.putObject("learningProgressReview");
        learning.put("active", active);
        if (!active) {
            return;
        }
        learning.put("draftRef", progress.getDraftRef());
        learning.put("planVersion", progress.getPlanVersion());
        learning.put("semanticVersion", progress.getSemanticVersion());
        learning.put("currentStageRef", request.getLearningPlanStageRef());
        var currentStage = progress.getSteps().stream()
                .filter(step -> request.getLearningPlanStageRef().equals(step.getStepRef()))
                .findFirst().orElse(null);
        if (currentStage == null) {
            learning.put("stageResolved", false);
            return;
        }
        learning.put("stageResolved", true);
        learning.put("stageStatus", currentStage.getStatus().name());
        learning.put("completionCriteria", currentStage.getCompletionCriteria());
        learning.put("evidenceType", currentStage.getEvidenceType() == null
                ? "" : currentStage.getEvidenceType().name());
        boolean skillLoaded = hasSuccessfulTool(context, "load_skill");
        boolean proposalSucceeded = hasSuccessfulTool(context, "propose_learning_progress");
        learning.put("teachingSkillLoaded", skillLoaded);
        learning.put("proposeProgressSucceeded", proposalSucceeded);
        // 日志只记录布尔事实和状态，不记录用户答案或计划正文。
        log.info("学习进度审查事实已组装，runId={}，stageStatus={}，skillLoaded={}，proposalSucceeded={}，stageResolved={}",
                context.getRunId(), currentStage.getStatus(), skillLoaded, proposalSucceeded, true);
    }

    // 只把成功执行的工具视为已完成动作，参数失败和用户拒绝都不能让回答直接通过。
    private boolean hasSuccessfulTool(AgentRunContext context, String toolName) {
        return context.getToolExecutions().stream().anyMatch(record ->
                toolName.equals(record.getToolName())
                        && record.getStatus() == ToolExecutionRecord.Status.SUCCEEDED
                        && record.getResult() != null && record.getResult().isSuccess());
    }

    // 审查只需要执行事实，不把规划模型写的教学约束和完成条件变成回答格式要求。
    private ObjectNode progressFacts(SessionGoalProgress progress) {
        ObjectNode facts = JSON.createObjectNode();
        facts.put("goalRef", progress.getGoalRef());
        facts.put("goal", progress.getGoal());
        facts.put("planVersion", progress.getPlanVersion());
        var steps = facts.putArray("steps");
        for (var step : progress.getSteps()) {
            ObjectNode item = steps.addObject();
            item.put("stepRef", step.getStepRef());
            item.put("position", step.getPosition());
            item.put("description", step.getDescription());
            item.put("status", step.getStatus().name());
            // 结果记录帮助核对过去的确认；仍不把其自然语言当作新授权。
            item.put("resultSummary", step.getResultSummary());
        }
        return facts;
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
            throw new ReviewFormatException("审查输出长度不合法");
        }
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (RuntimeException exception) {
            // Jackson 不完整 JSON、尾随内容和重复字段都属于模型协议格式错误。
            throw new ReviewFormatException("审查 JSON 无法解析");
        }
        if (root == null) {
            throw new ReviewFormatException("审查 JSON 为空");
        }
        if (!root.isObject() || !root.propertyNames().equals(FIELDS)) {
            throw new ReviewFormatException("审查字段不完整或含额外字段");
        }
        String actionText = requiredText(root, "action", 30);
        String reason = requiredText(root, "reason", 800);
        String instruction = requiredText(root, "instruction", 1_200);
        AnswerReviewResult.Action action;
        try {
            action = AnswerReviewResult.Action.valueOf(actionText);
        } catch (IllegalArgumentException exception) {
            throw new ReviewFormatException("审查动作不合法");
        }
        if (action == AnswerReviewResult.Action.UNAVAILABLE
                || (action != AnswerReviewResult.Action.PASS && instruction.isBlank())) {
            throw new ReviewFormatException("审查动作或建议不合法");
        }
        return new AnswerReviewResult(action, reason, instruction);
    }

    // 只接收短字符串，拒绝模型把对象或数组塞入解释字段。
    private String requiredText(JsonNode node, String field, int limit) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asString().length() > limit) {
            throw new ReviewFormatException("审查字段类型或长度不合法");
        }
        return value.asString();
    }

    // 单独标记模型协议错误，避免把网络或后端故障误当成格式错误反复请求。
    private static class ReviewFormatException extends RuntimeException {
        private ReviewFormatException(String message) {
            super(message);
        }
    }
}
