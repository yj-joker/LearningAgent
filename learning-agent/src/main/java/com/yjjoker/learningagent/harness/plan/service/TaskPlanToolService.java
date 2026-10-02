package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskStepRequest;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 负责把模型的计划修改请求转换成后端完整步骤快照；审批和事务仍由 Harness 与计划服务负责。
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskPlanToolService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Set<String> ROOT_FIELDS = Set.of("expectedVersion", "steps");
    private static final Set<String> STEP_FIELDS = Set.of(
            "stepRef", "description", "completionCriteria", "status", "resultSummary");

    private final SessionGoalContext context;
    private final SessionGoalService goals;

    // 返回计划修改工具的 JSON Schema，模型只能提交完整步骤列表。
    public Map<String, Object> schema() {
        Map<String, Object> step = Map.of(
                "type", "object",
                "properties", Map.of(
                        "stepRef", Map.of("type", List.of("string", "null"), "maxLength", 80,
                                "description", "原样复制当前计划中的 stepRef；新增步骤填 null"),
                        "description", text("步骤描述；已有步骤可修改，新增步骤必填", AgentTaskPlanService.MAX_STEP_TEXT_LENGTH),
                        "completionCriteria", text("步骤完成条件；已有步骤可修改，新增步骤必填", AgentTaskPlanService.MAX_STEP_TEXT_LENGTH),
                        "status", Map.of("type", "string", "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED", "BLOCKED", "CANCELED")),
                        "resultSummary", Map.of("type", List.of("string", "null"), "maxLength", AgentTaskPlanService.MAX_RESULT_LENGTH,
                                "description", "完成、阻塞或取消时填写原因；待执行时填 null")),
                "required", List.of("stepRef", "description", "completionCriteria", "status", "resultSummary"),
                "additionalProperties", false);
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "expectedVersion", Map.of("type", "integer", "minimum", 1),
                        "steps", Map.of("type", "array", "minItems", 1,
                                "maxItems", AgentTaskPlanService.MAX_STEPS, "items", step)),
                "required", List.of("expectedVersion", "steps"),
                "additionalProperties", false);
    }

    // 审批前只解析并校验完整快照，execute=false 时不修改内存和数据库。
    public ToolExecutionResult update(String input, boolean execute) {
        try {
            SessionGoalSnapshot expected = context.require();
            JsonNode root = object(input);
            long version = readVersion(root);
            if (expected.getCurrentPlan().getVersion() != version) {
                throw new ClientDataErrorException("计划版本已变化，请先读取最新计划");
            }
            UpdateTaskPlanRequest request = buildRequest(expected.getCurrentPlan(), root);
            goals.validateProgress(expected, request);
            log.info("计划结构修改已校验，sessionId={}，planVersion={}，stepCount={}，execute={}",
                    expected.getState().getSessionId(), version, request.getSteps().size(), execute);
            if (!execute) {
                return ToolExecutionResult.success("计划修改已校验，等待用户确认，尚未写入");
            }
            SessionGoalSnapshot updated = goals.updateProgress(expected, request);
            context.bind(updated);
            log.info("计划结构修改已提交，sessionId={}，oldVersion={}，newVersion={}，stepCount={}",
                    updated.getState().getSessionId(), version, updated.getCurrentPlan().getVersion(),
                    updated.getCurrentPlan().getSteps().size());
            return ToolExecutionResult.success(JSON.writeValueAsString(SessionGoalProgress.from(updated)));
        } catch (SecurityException exception) {
            log.warn("计划结构修改被拒绝，原因=无有效专注上下文");
            return ToolExecutionResult.failure("PLAN_ACCESS_DENIED", "计划修改只允许当前用户的专注模式使用", false);
        } catch (ClientDataErrorException | IllegalArgumentException | JacksonException exception) {
            log.warn("计划结构修改参数未通过，errorType={}", exception.getClass().getSimpleName());
            String message = exception instanceof JacksonException
                    ? "计划修改必须是合法 JSON 对象，不能有重复字段或尾随内容"
                    : exception.getMessage();
            return ToolExecutionResult.failure("INVALID_TASK_PLAN_UPDATE", message + "；请先读取最新计划并复制新的 stepRef", true);
        }
    }

    // 生成审批页面使用的简短说明；真实参数仍保存在通用审批检查点中。
    public String approvalReason(String input) {
        try {
            JsonNode root = object(input);
            SessionGoalSnapshot snapshot = context.require();
            int count = root.path("steps").isArray() ? root.path("steps").size() : 0;
            return "修改当前专注计划的完整步骤列表（计划版本 " + snapshot.getCurrentPlan().getVersion()
                    + "，提交 " + count + " 个步骤）；批准后才会生成新计划版本。";
        } catch (RuntimeException exception) {
            return "修改当前专注计划；批准后才会写入新的计划版本。";
        }
    }

    // 严格读取根对象，拒绝额外字段、重复字段、尾随内容和过大请求。
    private JsonNode object(String input) {
        if (input == null || input.length() > 40_000) {
            throw new IllegalArgumentException("计划修改参数过大");
        }
        JsonNode root = JSON.readTree(input);
        if (root == null || !root.isObject() || !names(root).equals(ROOT_FIELDS)) {
            throw new IllegalArgumentException("计划修改必须只包含 expectedVersion 和 steps");
        }
        return root;
    }

    // 读取客户端看到的计划版本；版本不是数据库 ID，作用是拒绝过期审批。
    private long readVersion(JsonNode root) {
        JsonNode version = root.path("expectedVersion");
        if (!version.isIntegralNumber() || version.longValue() < 1 || version.longValue() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("expectedVersion 必须是有效的正整数");
        }
        return version.longValue();
    }

    // 将模型可见的 stepRef 解析为后端内部 stepId，并保留用户提交的新顺序。
    private UpdateTaskPlanRequest buildRequest(AgentTaskPlan plan, JsonNode root) {
        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.isEmpty() || steps.size() > AgentTaskPlanService.MAX_STEPS) {
            throw new IllegalArgumentException("steps 数量必须在 1 到 " + AgentTaskPlanService.MAX_STEPS + " 之间");
        }
        Map<String, AgentTaskStep> byRef = new LinkedHashMap<>();
        for (AgentTaskStep step : plan.getSteps()) {
            byRef.put(TaskProgressToolService.stepRef(plan, step), step);
        }
        Set<String> included = new HashSet<>();
        List<UpdateTaskStepRequest> updates = new ArrayList<>();
        for (JsonNode node : steps) {
            if (!node.isObject() || !names(node).equals(STEP_FIELDS)) {
                throw new IllegalArgumentException("每个步骤必须只包含 stepRef、description、completionCriteria、status、resultSummary");
            }
            String stepRef = nullableText(node.path("stepRef"), 80, "stepRef");
            AgentTaskStep original = stepRef == null ? null : byRef.get(stepRef);
            if (stepRef != null && (original == null || !included.add(stepRef))) {
                throw new IllegalArgumentException("步骤引用已过期、不属于当前计划或重复");
            }
            UpdateTaskStepRequest update = new UpdateTaskStepRequest();
            update.setStepId(original == null ? null : original.getStepId());
            update.setDescription(requiredText(node.path("description"), AgentTaskPlanService.MAX_STEP_TEXT_LENGTH, "步骤描述"));
            update.setCompletionCriteria(requiredText(node.path("completionCriteria"), AgentTaskPlanService.MAX_STEP_TEXT_LENGTH, "完成条件"));
            update.setStatus(status(node.path("status")));
            update.setResultSummary(nullableText(node.path("resultSummary"), AgentTaskPlanService.MAX_RESULT_LENGTH, "resultSummary"));
            updates.add(update);
        }
        if (included.size() != plan.getSteps().size()) {
            throw new IllegalArgumentException("修改请求必须保留全部旧步骤；不再执行的步骤请标记 CANCELED");
        }
        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest();
        request.setExpectedVersion(plan.getVersion());
        request.setSteps(updates);
        return request;
    }

    // 只允许步骤状态枚举值，未知状态不交给数据库解释。
    private AgentTaskStepStatus status(JsonNode node) {
        String value = requiredText(node, 20, "status");
        try {
            return AgentTaskStepStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("步骤状态不合法");
        }
    }

    // 读取必填文本并限制长度，避免把长篇资料放进计划步骤。
    private String requiredText(JsonNode node, int limit, String name) {
        String value = nullableText(node, limit, name);
        if (value == null) throw new IllegalArgumentException(name + "不能为空");
        return value;
    }

    // 读取允许为空或 null 的文本；空字符串按 null 处理。
    private String nullableText(JsonNode node, int limit, String name) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException(name + "必须是字符串或 null");
        String value = node.asString().strip();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException(name + "不能超过 " + limit + " 个字符");
        }
        return value;
    }

    // 取得 JSON 对象字段集合，用于拒绝模型偷偷附加会话 ID 等字段。
    private Set<String> names(JsonNode node) {
        Set<String> names = new HashSet<>();
        for (String name : node.propertyNames()) {
            names.add(name);
        }
        return names;
    }

    // 生成 Schema 中的字符串字段定义。
    private Map<String, Object> text(String description, int limit) {
        return Map.of("type", "string", "minLength", 1, "maxLength", limit, "description", description);
    }
}
