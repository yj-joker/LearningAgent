package com.yjjoker.learningagent.harness.learningplan.service;

import com.yjjoker.learningagent.dto.CreateLearningPlanDraftRequest;
import com.yjjoker.learningagent.dto.ActivateLearningPlanDraftRequest;
import com.yjjoker.learningagent.dto.LearningPlanDraftStepRequest;
import com.yjjoker.learningagent.dto.UpdateLearningPlanDraftRequest;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.service.LearningPlanDraftService;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 把模型 JSON 转成草案请求对象；它不保存业务数据，真正写入统一交给草案 Service。
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningPlanDraftToolService {
    private static final JsonMapper JSON = new JsonMapper();
    private final LearningPlanDraftService service;

    // 读取草案索引，返回给模型的内容来自数据库，不依赖模型上轮记忆。
    public ToolExecutionResult list() {
        try {
            // 工具只返回计划索引和步骤数量，完整步骤由 get_learning_plan_draft 按需读取。
            List<Map<String, Object>> index = service.listCurrentUser().stream().map(draft -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("draftRef", draft.getDraftRef());
                item.put("title", draft.getTitle());
                item.put("objective", draft.getObjective());
                item.put("status", draft.getStatus());
                item.put("formal", draft.isFormal());
                item.put("source", draft.getSource());
                item.put("version", draft.getVersion());
                item.put("stepCount", draft.getSteps().size());
                return item;
            }).toList();
            return ToolExecutionResult.success(JSON.writeValueAsString(index));
        } catch (RuntimeException exception) {
            log.warn("Agent 读取学习计划草案列表失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("DRAFT_READ_FAILED", "读取学习计划草案失败，请稍后重试", false);
        }
    }

    // 按稳定 draftRef 读取完整草案和步骤。
    public ToolExecutionResult get(String input) {
        try {
            JsonNode root = object(input, SetMode.GET);
            String draftRef = requiredText(root, "draftRef", 64);
            return ToolExecutionResult.success(JSON.writeValueAsString(service.getCurrentUser(draftRef)));
        } catch (NotFountException exception) {
            return ToolExecutionResult.failure("DRAFT_NOT_FOUND", "草案不存在，请先使用 list_learning_plan_drafts 刷新索引", true);
        } catch (ClientDataErrorException | IllegalArgumentException exception) {
            return invalidArgument(exception.getMessage());
        } catch (JacksonException exception) {
            return invalidArgument("参数不是有效的 JSON 对象");
        } catch (RuntimeException exception) {
            log.warn("Agent 读取学习计划草案失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("DRAFT_READ_FAILED", "读取学习计划草案失败，请稍后重试", false);
        }
    }

    // 预检创建参数；审批前不写数据库。
    public ToolExecutionResult validateCreate(String input) {
        try {
            CreateLearningPlanDraftRequest request = parseCreate(input);
            service.validateAgentCreate(request);
            return ToolExecutionResult.success("草案参数检查通过，等待用户审批；当前仍未保存");
        } catch (ClientDataErrorException | IllegalArgumentException exception) {
            return invalidArgument(exception.getMessage());
        } catch (JacksonException exception) {
            return invalidArgument("参数不是有效的 JSON 对象");
        } catch (RuntimeException exception) {
            log.warn("Agent 创建草案预检失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("DRAFT_VALIDATION_FAILED", "草案预检失败，请稍后重试", false);
        }
    }

    // 审批页面只展示简短摘要；完整参数由通用检查点保存。
    public String createApprovalReason(String input) {
        return "创建一份未正式生效的学习计划草案（步骤内容将保存到当前用户草案）";
    }

    // 批准后才正式创建草案，并返回稳定 draftRef 和版本。
    public ToolExecutionResult create(String input) {
        try {
            CreateLearningPlanDraftRequest request = parseCreate(input);
            return ToolExecutionResult.success(JSON.writeValueAsString(service.createAgent(request)));
        } catch (ClientDataErrorException | IllegalArgumentException exception) {
            return invalidArgument(exception.getMessage());
        } catch (JacksonException exception) {
            return invalidArgument("参数不是有效的 JSON 对象");
        } catch (RuntimeException exception) {
            log.warn("Agent 创建学习计划草案失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("DRAFT_WRITE_FAILED", "保存学习计划草案失败，请稍后重试", false);
        }
    }

    // 预检更新目标和版本；审批前不修改数据库。
    public ToolExecutionResult validateUpdate(String input) {
        try {
            UpdatePayload payload = parseUpdate(input);
            service.validateAgentUpdate(payload.draftRef(), payload.request());
            return ToolExecutionResult.success("草案参数和版本检查通过，等待用户审批；当前仍未保存");
        } catch (NotFountException exception) {
            return ToolExecutionResult.failure("DRAFT_NOT_FOUND", "草案不存在，请先刷新草案索引", true);
        } catch (ClientDataErrorException | IllegalArgumentException exception) {
            return invalidArgument(exception.getMessage());
        } catch (JacksonException exception) {
            return invalidArgument("参数不是有效的 JSON 对象");
        } catch (RuntimeException exception) {
            log.warn("Agent 更新草案预检失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("DRAFT_VALIDATION_FAILED", "草案预检失败，请稍后重试", false);
        }
    }

    // 审批页面显示更新意图；完整参数由检查点保存，DRAFT 和 ACTIVE 都可更新。
    public String updateApprovalReason(String input) {
        return "修改同一份学习计划；批准后版本递增，并保留当前 DRAFT 或 ACTIVE 状态";
    }

    // 批准后使用原 draftRef 和 expectedVersion 更新同一份草案。
    public ToolExecutionResult update(String input) {
        try {
            UpdatePayload payload = parseUpdate(input);
            return ToolExecutionResult.success(JSON.writeValueAsString(
                    service.updateAgent(payload.draftRef(), payload.request())));
        } catch (NotFountException exception) {
            return ToolExecutionResult.failure("DRAFT_NOT_FOUND", "草案不存在，请先刷新草案索引", true);
        } catch (ClientDataErrorException | IllegalArgumentException exception) {
            return invalidArgument(exception.getMessage());
        } catch (JacksonException exception) {
            return invalidArgument("参数不是有效的 JSON 对象");
        } catch (RuntimeException exception) {
            log.warn("Agent 更新学习计划草案失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("DRAFT_WRITE_FAILED", "更新学习计划草案失败，请稍后重试", false);
        }
    }

    // 预检正式生效请求；审批前只读取版本和状态，不修改草案。
    public ToolExecutionResult validateActivate(String input) {
        try {
            ActivateLearningPlanDraftRequest request = parseActivate(input);
            service.validateAgentActivation(request.getDraftRef(), request.getExpectedVersion());
            return ToolExecutionResult.success("草案版本和状态检查通过，等待用户审批；当前仍未正式生效");
        } catch (NotFountException exception) {
            return ToolExecutionResult.failure("DRAFT_NOT_FOUND", "草案不存在或已经正式生效，请先刷新索引", true);
        } catch (ClientDataErrorException | IllegalArgumentException exception) {
            return invalidArgument(exception.getMessage());
        } catch (JacksonException exception) {
            return invalidArgument("参数不是有效的 JSON 对象");
        } catch (RuntimeException exception) {
            log.warn("Agent 确认学习计划预检失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("DRAFT_ACTIVATION_VALIDATION_FAILED", "确认草案预检失败，请稍后重试", false);
        }
    }

    // 审批卡只说明状态变化，不能把待审批草案写成已经生效。
    public String activateApprovalReason(String input) {
        return "确认同一份学习计划草案正式生效；批准后状态变为 ACTIVE";
    }

    // 批准后把同一份草案切换为 ACTIVE，返回新的版本和正式状态。
    public ToolExecutionResult activate(String input) {
        try {
            ActivateLearningPlanDraftRequest request = parseActivate(input);
            return ToolExecutionResult.success(JSON.writeValueAsString(
                    service.activateAgent(request.getDraftRef(), request.getExpectedVersion())));
        } catch (NotFountException exception) {
            return ToolExecutionResult.failure("DRAFT_NOT_FOUND", "草案不存在或已经正式生效，请先刷新索引", true);
        } catch (ClientDataErrorException | IllegalArgumentException exception) {
            return invalidArgument(exception.getMessage());
        } catch (JacksonException exception) {
            return invalidArgument("参数不是有效的 JSON 对象");
        } catch (RuntimeException exception) {
            log.warn("Agent 确认学习计划失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("DRAFT_ACTIVATION_FAILED", "学习计划正式生效失败，请稍后重试", false);
        }
    }

    // 创建工具只暴露草案字段，不允许模型传 userId、sessionId 或数据库主键。
    public Map<String, Object> createSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", text("草案标题", 200));
        properties.put("objective", text("总体学习目标", 2000));
        properties.put("learnerProfile", optionalText("学习者基础情况", 1000));
        properties.put("weeklyCommitment", optionalText("每周可投入时间", 500));
        properties.put("constraints", optionalText("限制条件", 2000));
        properties.put("steps", stepsSchema(false));
        return schema(properties, List.of("title", "objective", "steps"));
    }

    // 更新工具必须提供稳定引用、最新版本和完整步骤列表。
    public Map<String, Object> updateSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("draftRef", text("当前索引中的草案引用，必须原样复制", 64));
        properties.put("expectedVersion", Map.of("type", "integer", "minimum", 1));
        properties.put("title", optionalText("新标题；不传表示保留原值", 200));
        properties.put("objective", optionalText("新学习目标；不传表示保留原值", 2000));
        properties.put("learnerProfile", optionalText("新基础情况；不传表示保留原值", 1000));
        properties.put("weeklyCommitment", optionalText("新每周投入；不传表示保留原值", 500));
        properties.put("constraints", optionalText("新限制条件；不传表示保留原值", 2000));
        properties.put("steps", stepsSchema(true));
        return schema(properties, List.of("draftRef", "expectedVersion", "steps"));
    }

    // 确认工具只接收稳定草案引用和读取到的版本，不允许模型提交用户身份或状态字段。
    public Map<String, Object> activateSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("draftRef", text("当前索引中的草案引用，必须原样复制", 64));
        properties.put("expectedVersion", Map.of("type", "integer", "minimum", 1));
        return schema(properties, List.of("draftRef", "expectedVersion"));
    }

    // 解析创建请求并拒绝未声明字段，避免模型偷偷附加用户或权限参数。
    private CreateLearningPlanDraftRequest parseCreate(String input) throws JacksonException {
        JsonNode root = object(input, SetMode.CREATE);
        CreateLearningPlanDraftRequest request = new CreateLearningPlanDraftRequest();
        request.setTitle(requiredText(root, "title", 200));
        request.setObjective(requiredText(root, "objective", 2000));
        request.setLearnerProfile(optionalText(root, "learnerProfile", 1000));
        request.setWeeklyCommitment(optionalText(root, "weeklyCommitment", 500));
        request.setConstraints(optionalText(root, "constraints", 2000));
        request.setSteps(parseSteps(root.get("steps"), false));
        return request;
    }

    // 解析更新请求；draftRef 由外层单独取出并作为目标校验。
    private UpdatePayload parseUpdate(String input) throws JacksonException {
        JsonNode root = object(input, SetMode.UPDATE);
        UpdateLearningPlanDraftRequest request = new UpdateLearningPlanDraftRequest();
        request.setDraftRef(requiredText(root, "draftRef", 64));
        JsonNode version = root.get("expectedVersion");
        if (version == null || !version.isIntegralNumber() || version.asLong() < 1) {
            throw new IllegalArgumentException("expectedVersion 必须是正整数");
        }
        request.setExpectedVersion(version.asLong());
        request.setTitle(optionalText(root, "title", 200));
        request.setObjective(optionalText(root, "objective", 2000));
        request.setLearnerProfile(optionalText(root, "learnerProfile", 1000));
        request.setWeeklyCommitment(optionalText(root, "weeklyCommitment", 500));
        request.setConstraints(optionalText(root, "constraints", 2000));
        request.setSteps(parseSteps(root.get("steps"), true));
        return new UpdatePayload(request.getDraftRef(), request);
    }

    // 解析步骤数组；更新时允许复制已有 stepRef，新步骤不带 stepRef。
    private List<LearningPlanDraftStepRequest> parseSteps(JsonNode node, boolean allowStepRef) {
        if (node == null || !node.isArray() || node.size() == 0 || node.size() > 12) {
            throw new IllegalArgumentException("steps 必须包含 1 到 12 个步骤");
        }
        List<LearningPlanDraftStepRequest> steps = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) throw new IllegalArgumentException("每个步骤必须是对象");
            List<String> names = new ArrayList<>();
            for (String name : item.propertyNames()) names.add(name);
            List<String> allowed = allowStepRef
                    ? List.of("stepRef", "description", "completionCriteria")
                    : List.of("description", "completionCriteria");
            if (!allowed.containsAll(names)) throw new IllegalArgumentException("步骤包含未允许的字段");
            LearningPlanDraftStepRequest step = new LearningPlanDraftStepRequest();
            if (allowStepRef && item.get("stepRef") != null && !item.get("stepRef").isNull()) {
                step.setStepRef(requiredText(item, "stepRef", 64));
            }
            step.setDescription(requiredText(item, "description", 1000));
            step.setCompletionCriteria(requiredText(item, "completionCriteria", 1000));
            steps.add(step);
        }
        return steps;
    }

    // 读取 JSON 对象并检查顶层字段集合。
    private JsonNode object(String input, SetMode mode) throws JacksonException {
        if (input == null || input.length() > 20_000) throw new IllegalArgumentException("工具参数过长");
        JsonNode root = JSON.readTree(input);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("参数必须是 JSON 对象");
        List<String> allowed = switch (mode) {
            case GET -> List.of("draftRef");
            case CREATE -> List.of("title", "objective", "learnerProfile", "weeklyCommitment", "constraints", "steps");
            case UPDATE -> List.of("draftRef", "expectedVersion", "title", "objective", "learnerProfile", "weeklyCommitment", "constraints", "steps");
            case ACTIVATE -> List.of("draftRef", "expectedVersion");
        };
        for (String name : root.propertyNames()) {
            if (!allowed.contains(name)) throw new IllegalArgumentException("参数包含未允许的字段：" + name);
        }
        return root;
    }

    // 读取必填字符串并限制长度。
    private String requiredText(JsonNode root, String name, int max) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asString().isBlank()
                || value.asString().strip().codePointCount(0, value.asString().strip().length()) > max) {
            throw new IllegalArgumentException(name + " 必须是非空字符串且不超过 " + max + " 个字符");
        }
        return value.asString().strip();
    }

    // 读取可选字符串；显式 null 和空字符串都按未提供处理。
    private String optionalText(JsonNode root, String name, int max) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(name + " 必须是字符串或 null");
        String text = value.asString().strip();
        if (text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) > max) throw new IllegalArgumentException(name + " 过长");
        return text;
    }

    // 生成工具参数 Schema，让模型知道字段边界；后端仍会再次校验。
    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    // 生成步骤数组 Schema；更新允许复制 stepRef，创建不允许伪造引用。
    private Map<String, Object> stepsSchema(boolean allowStepRef) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (allowStepRef) fields.put("stepRef", text("已有步骤的稳定引用；新步骤不填", 64));
        fields.put("description", text("步骤内容", 1000));
        fields.put("completionCriteria", text("完成条件", 1000));
        List<String> required = List.of("description", "completionCriteria");
        return Map.of("type", "array", "minItems", 1, "maxItems", 12,
                "items", schema(fields, required));
    }

    private Map<String, Object> text(String description, int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max, "description", description);
    }

    private Map<String, Object> optionalText(String description, int max) {
        return Map.of("type", "string", "maxLength", max, "description", description);
    }

    private ToolExecutionResult invalidArgument(String message) {
        return ToolExecutionResult.failure("INVALID_ARGUMENT", message == null ? "草案参数不合法" : message, true);
    }

    // 解析确认参数并复用 DTO，保证预检和正式执行使用完全相同的输入。
    private ActivateLearningPlanDraftRequest parseActivate(String input) throws JacksonException {
        JsonNode root = object(input, SetMode.ACTIVATE);
        ActivateLearningPlanDraftRequest request = new ActivateLearningPlanDraftRequest();
        request.setDraftRef(requiredText(root, "draftRef", 64));
        JsonNode version = root.get("expectedVersion");
        if (version == null || !version.isIntegralNumber() || version.asLong() < 1) {
            throw new IllegalArgumentException("expectedVersion 必须是正整数");
        }
        request.setExpectedVersion(version.asLong());
        return request;
    }

    private enum SetMode { GET, CREATE, UPDATE, ACTIVATE }

    // 保存更新解析结果，避免同一份 JSON 被重复解析时目标引用和版本不一致。
    private static final class UpdatePayload {
        private final String draftRef;
        private final UpdateLearningPlanDraftRequest request;

        private UpdatePayload(String draftRef, UpdateLearningPlanDraftRequest request) {
            this.draftRef = draftRef;
            this.request = request;
        }

        private String draftRef() { return draftRef; }

        private UpdateLearningPlanDraftRequest request() { return request; }
    }
}
