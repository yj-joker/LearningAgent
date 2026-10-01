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
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskStepRequest;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 只负责调用规划提示词并解析计划，不保存计划，也不执行业务工具。
@Service
@Slf4j
public class FocusPlanPlanner {

    // 不接受重复字段和尾随 JSON，避免同一份计划出现两种解释。
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_RESPONSE_CHARACTERS = 6000;

    private final LlmClient llmClient;
    private final LlmRetryExecutor retryExecutor;
    // 只读取名称和参数结构供规划参考，规划阶段不能执行这些工具。
    private final ToolRegistry toolRegistry;

    // 复用现有模型客户端；与主循环的区别是提示词和不提供工具。
    public FocusPlanPlanner(LlmClient llmClient, LlmRetryExecutor retryExecutor, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.retryExecutor = retryExecutor;
        this.toolRegistry = toolRegistry;
    }

    // 首次格式错误时反馈原因再生成一次；整个过程不写数据库。
    public CreateTaskPlanRequest createPlan(String runId, String userMessage) {
        List<LlmMessage> messages = new ArrayList<>(List.of(
                LlmMessage.system(planningPrompt()),
                LlmMessage.user(userMessage)
        ));
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.info("专注规划请求开始，runId={}，attempt={}，maxAttempts={}，inputCharacters={}",
                    runId, attempt, MAX_ATTEMPTS, messages.stream().mapToInt(m -> m.getContent().length()).sum());
            // 网络异常只走已有网络重试，不能再被下面的格式修复重复处理。
            LlmResponse response = retryExecutor.generateWithoutTools("focus-plan", llmClient, List.copyOf(messages));
            try {
                if (!(response instanceof TextLlmResponse text)) {
                    throw invalidPlan("必须返回计划 JSON，不能调用工具");
                }
                CreateTaskPlanRequest request = parse(text.content());
                log.info("专注规划校验通过，runId={}，attempt={}，stepCount={}，responseCharacters={}",
                        runId, attempt, request.getSteps().size(), text.content().length());
                return request;
            } catch (HarnessException formatError) {
                // 只记录后端生成的错误原因，不记录模型原文或用户目标。
                log.warn("专注规划格式校验失败，runId={}，attempt={}，willRepair={}，reason={}",
                        runId, attempt, attempt < MAX_ATTEMPTS, formatError.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw invalidPlan("专注计划生成失败，修复后仍不符合格式要求，本次未进入执行阶段");
                }
                messages.add(LlmMessage.user("上次计划未通过后端校验：" + formatError.getMessage()
                        + "。计划尚未保存，请依据原始用户目标重新返回完整 JSON。"));
            }
        }
        throw new IllegalStateException("专注规划流程未返回结果");
    }

    // 给规划器真实能力目录，避免拆出系统无法执行的步骤或凭空增加必填参数。
    private String planningPrompt() {
        List<Map<String, Object>> definitions = toolRegistry.getAllTools().stream()
                .map(tool -> Map.<String, Object>of("name", tool.name(),
                        "description", tool.description(), "parameters", tool.parametersSchema(),
                        "requiresUserApproval", tool.requiresUserApproval())).toList();
        String catalog = JSON.writeValueAsString(definitions);
        // 本阶段目录整体有上限；超限就停止，不截断 JSON 让模型猜缺失定义。
        if (catalog.length() > 20_000) {
            throw invalidPlan("规划工具目录过大，请缩小本次可用能力范围");
        }
        return AgentSystemPrompt.FOCUS_PLANNER_PROMPT
                + "\n后续主 AgentLoop 可执行的工具定义如下；你只据此拆解步骤，不在规划阶段执行：\n" + catalog;
    }

    // 校验完整 JSON 和短计划限制，不截取正文中的某一段冒充合法响应。
    private CreateTaskPlanRequest parse(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_RESPONSE_CHARACTERS) {
            throw invalidPlan("计划正文不能为空且不能超过 6000 个字符");
        }
        try {
            JsonNode root = JSON.readTree(content);
            requireFields(root, Set.of("goal", "constraints", "steps"));
            CreateTaskPlanRequest request = new CreateTaskPlanRequest();
            request.setGoal(readText(root, "goal", 500));
            // 限制是可选内容，不把 null、空字符串或缺失字段当作模型已确认的新要求。
            JsonNode constraints = root.path("constraints");
            if (!constraints.isMissingNode() && !constraints.isNull()) {
                if (!constraints.isTextual() || constraints.asString().codePointCount(0, constraints.asString().length()) > 500) {
                    throw invalidPlan("constraints 必须是 500 字以内的字符串");
                }
                request.setConstraints(constraints.asString().strip());
            }
            JsonNode nodes = root.path("steps");
            if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 3) {
                throw invalidPlan("steps 必须包含 1 到 3 个步骤");
            }
            List<CreateTaskStepRequest> steps = new ArrayList<>();
            for (JsonNode node : nodes) {
                requireFields(node, Set.of("description", "completionCriteria"));
                CreateTaskStepRequest step = new CreateTaskStepRequest();
                step.setDescription(readText(node, "description", 200));
                step.setCompletionCriteria(readText(node, "completionCriteria", 200));
                steps.add(step);
            }
            request.setSteps(List.copyOf(steps));
            return request;
        } catch (JacksonException exception) {
            // 不把解析器包含的原始文本放进日志或修复提示。
            throw invalidPlan("必须返回一个完整 JSON 对象，不能包含 Markdown、重复字段或尾随内容");
        }
    }

    // 拒绝模型自定 ID、状态和额外字段，归属与初始进度由后端生成。
    private void requireFields(JsonNode node, Set<String> allowedFields) {
        if (node == null || !node.isObject() || !allowedFields.containsAll(node.propertyNames())) {
            throw invalidPlan("计划和步骤必须是对象，并且只能包含约定字段");
        }
    }

    // 文本必须非空且简短；数字不会被自动转成文字。
    private String readText(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asString().isBlank()) {
            throw invalidPlan(field + " 必须是非空字符串");
        }
        String text = value.asString().strip();
        if (text.codePointCount(0, text.length()) > maximum) {
            throw invalidPlan(field + " 不能超过 " + maximum + " 字");
        }
        return text;
    }

    // 规划结果属于模型边界格式错误，不应继续进入业务工具执行。
    private HarnessException invalidPlan(String message) {
        return new HarnessException(HarnessError.of(
                HarnessErrorCode.LLM_INVALID_RESPONSE,
                message,
                false,
                HarnessErrorSource.LLM
        ));
    }
}
