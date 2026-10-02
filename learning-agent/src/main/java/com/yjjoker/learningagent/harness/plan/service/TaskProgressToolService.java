package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.plan.dto.StepProgressChange;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 模型提出进度建议，后端校验，用户审批后才写入；不把“说完了”当成“学会了”。
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskProgressToolService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS = Set.of("stepRef", "status", "reason", "completionBasis", "userEvidence");
    private static final Set<String> BASES = Set.of("DIALOGUE_EVIDENCE", "USER_CONFIRMED", "NOT_APPLICABLE");
    private final SessionGoalContext context;
    private final SessionGoalService goals;

    // 引用包含目标、计划版本和位置；重排步骤或切换目标后，旧引用不能命中新步骤。
    public static String stepRef(AgentTaskPlan plan, AgentTaskStep step) {
        return "goal-" + plan.getGoalNumber() + "-v" + plan.getVersion() + "-step-" + step.getPosition();
    }

    // 一次申请可结束当前步骤并开始下一步；同批变化一起审批、一起提交。
    public Map<String, Object> schema() {
        Map<String, Object> properties = Map.of(
                "stepRef", text("原样复制最新计划中的步骤引用，不使用数据库 ID", 80),
                "status", Map.of("type", "string", "enum", List.of("IN_PROGRESS", "COMPLETED", "BLOCKED", "CANCELED"),
                        "description", "IN_PROGRESS 开始，COMPLETED 完成，BLOCKED 受阻，CANCELED 取消；PENDING 不能直接完成，须先申请开始"),
                "reason", text("本次成果或变更原因，简短说明为什么申请这个状态", 500),
                // 明确与 status 的对应关系，避免把用户要求开始误填成完成确认。
                "completionBasis", Map.of("type", "string", "enum", List.of("DIALOGUE_EVIDENCE", "USER_CONFIRMED", "NOT_APPLICABLE"),
                        "description", "仅 status=COMPLETED 时选择 DIALOGUE_EVIDENCE（有用户对话依据）或 USER_CONFIRMED（用户明确通过或继续）；其他状态必须填 NOT_APPLICABLE，包括用户要求开始"),
                "userEvidence", Map.of("type", "string", "maxLength", 800,
                        "description", "原样引用当前上下文中的用户话语；DIALOGUE_EVIDENCE 必填，其他情况可为空字符串"));
        Map<String, Object> change = Map.of("type", "object", "properties", properties,
                "required", List.of("stepRef", "status", "reason", "completionBasis", "userEvidence"),
                "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.of("updates", Map.of("type", "array",
                "minItems", 1, "maxItems", AgentTaskPlanService.MAX_STEPS, "items", change)),
                "required", List.of("updates"), "additionalProperties", false);
    }

    // 预检只读；正式执行仍重复校验，防止审批期间目标或步骤已经变化。
    public ToolExecutionResult update(String input, boolean execute) {
        try {
            SessionGoalSnapshot expected = context.require();
            if (context.isExplicitProgressReadOnlyRequest()) {
                log.info("明确的只读进度请求拒绝状态写入工具，sessionId={}", expected.getState().getSessionId());
                return ToolExecutionResult.failure("PROGRESS_READ_ONLY",
                        "当前用户消息只是查询进度，不能申请修改步骤；请调用 get_session_goal_progress", true);
            }
            List<StepProgressChange> changes = parse(input);
            UpdateTaskPlanRequest request = buildRequest(expected.getCurrentPlan(), changes);
            goals.validateProgress(expected, request);
            log.info("步骤进度申请已校验，sessionId={}，planVersion={}，changeCount={}，execute={}",
                    expected.getState().getSessionId(), expected.getCurrentPlan().getVersion(), changes.size(), execute);
            if (!execute) return ToolExecutionResult.success("状态变更已校验，等待用户确认，尚未更新进度");
            SessionGoalSnapshot updated = goals.updateProgress(expected, request);
            // 事务提交成功后才更新内存；Harness 随后将最新状态发送给模型。
            context.bind(updated);
            log.info("步骤进度审批执行成功，sessionId={}，planVersion={}，changeCount={}",
                    updated.getState().getSessionId(), updated.getCurrentPlan().getVersion(), changes.size());
            return ToolExecutionResult.success(resultJson(updated));
        } catch (SecurityException exception) {
            log.warn("步骤进度申请被拒绝，原因=无有效专注上下文");
            return ToolExecutionResult.failure("STEP_ACCESS_DENIED", "只能更新当前用户专注会话中的步骤", false);
        } catch (ClientDataErrorException | IllegalArgumentException | JacksonException exception) {
            // 只输出后端规则，不把用户原话、完整理由或异常正文写入日志。
            log.warn("步骤进度申请未通过，errorType={}", exception.getClass().getSimpleName());
            // JSON 解析异常不回显原输入；业务校验原因交给模型，便于修正下一次申请。
            String message = exception instanceof JacksonException
                    ? "进度申请必须是一个合法 JSON 对象，不能有重复字段或尾随内容"
                    : exception.getMessage();
            message += "；请复制最新 stepRef，并按状态填写完成依据与真实用户原话";
            return ToolExecutionResult.failure("INVALID_STEP_PROGRESS", message, true);
        }
        // 数据库等系统故障不伪装成参数错误，继续交给 Harness 统一终止和记录。
    }

    // 审批说明引用数据库中的步骤和完成条件；模型的理由明确标注为待用户核对。
    public String approvalReason(String input) {
        AgentTaskPlan plan = context.require().getCurrentPlan();
        List<StepProgressChange> changes = parse(input);
        StringBuilder text = new StringBuilder("申请更新步骤进度；批准后继续任务才会写入。确认进度不等于验证掌握。\n");
        for (StepProgressChange change : changes) {
            AgentTaskStep step = resolve(plan, change.getStepRef());
            text.append("步骤 ").append(step.getPosition()).append("：").append(step.getDescription())
                    .append("；条件：").append(step.getCompletionCriteria())
                    .append("；状态：").append(step.getStatus()).append(" → ").append(change.getStatus())
                    .append("；依据：").append(basisLabel(change.getCompletionBasis()))
                    .append("；模型理由（待确认）：").append(change.getReason()).append("\n");
        }
        // 审批表说明最多 500 字；完整理由和原话仍保存在只读工具参数里，不丢弃原申请。
        String value = text.toString();
        int count = value.codePointCount(0, value.length());
        return count <= 480 ? value : value.substring(0, value.offsetByCodePoints(0, 450)) + "…完整申请见下方参数。";
    }

    // 严格解析一组状态变化，拒绝额外字段、重复字段和无界输入。
    private List<StepProgressChange> parse(String input) {
        if (input == null || input.length() > 40_000) throw new IllegalArgumentException("进度申请过大");
        JsonNode root = JSON.readTree(input);
        if (root == null || !root.isObject() || !root.propertyNames().equals(Set.of("updates"))) {
            throw new IllegalArgumentException("必须只提供 updates");
        }
        JsonNode updates = root.path("updates");
        if (!updates.isArray() || updates.isEmpty() || updates.size() > AgentTaskPlanService.MAX_STEPS) {
            throw new IllegalArgumentException("更新步骤数量不合法");
        }
        List<StepProgressChange> changes = new ArrayList<>();
        for (JsonNode node : updates) {
            if (!node.isObject() || !node.propertyNames().equals(FIELDS)) throw new IllegalArgumentException("步骤字段不完整");
            StepProgressChange change = new StepProgressChange();
            change.setStepRef(readText(node, "stepRef", 80, false));
            change.setStatus(AgentTaskStepStatus.valueOf(readText(node, "status", 20, false)));
            if (change.getStatus() == AgentTaskStepStatus.PENDING) throw new IllegalArgumentException("不能退回待执行");
            change.setReason(readText(node, "reason", 500, false));
            change.setCompletionBasis(readText(node, "completionBasis", 32, false));
            change.setUserEvidence(readText(node, "userEvidence", 800, true));
            if (!BASES.contains(change.getCompletionBasis())) throw new IllegalArgumentException("完成依据不合法");
            validateBasis(change);
            changes.add(change);
        }
        return changes;
    }

    // 检查原话存在，不用正则猜测用户是否“学会”；用户意愿最终由真实审批决定。
    private void validateBasis(StepProgressChange change) {
        boolean completed = change.getStatus() == AgentTaskStepStatus.COMPLETED;
        boolean notApplicable = "NOT_APPLICABLE".equals(change.getCompletionBasis());
        if ((completed && notApplicable) || (!completed && !notApplicable)) {
            throw new IllegalArgumentException("完成与非完成状态的依据类型不匹配");
        }
        String evidence = change.getUserEvidence();
        if ("DIALOGUE_EVIDENCE".equals(change.getCompletionBasis()) && evidence.isBlank()) {
            throw new IllegalArgumentException("依据对话完成时必须引用用户原话");
        }
        if (!evidence.isBlank() && !context.containsUserEvidence(evidence)) {
            throw new IllegalArgumentException("引用未出现在本轮可见的用户消息中");
        }
        // TODO 接入带检索、引用核验或代码验证工具的知识审核 Agent；目前不判断回答正确或用户已掌握。
    }

    // 从服务端快照构造完整更新，只允许替换指定步骤的状态与说明。
    private UpdateTaskPlanRequest buildRequest(AgentTaskPlan plan, List<StepProgressChange> changes) {
        Map<String, StepProgressChange> byId = new LinkedHashMap<>();
        for (StepProgressChange change : changes) {
            AgentTaskStep step = resolve(plan, change.getStepRef());
            if (byId.put(step.getStepId(), change) != null) throw new IllegalArgumentException("同一步骤不能重复申请");
        }
        List<UpdateTaskStepRequest> updates = new ArrayList<>();
        for (AgentTaskStep step : plan.getSteps()) {
            UpdateTaskStepRequest update = new UpdateTaskStepRequest();
            update.setStepId(step.getStepId());
            update.setDescription(step.getDescription());
            update.setCompletionCriteria(step.getCompletionCriteria());
            update.setStatus(step.getStatus());
            update.setResultSummary(step.getResultSummary());
            StepProgressChange change = byId.get(step.getStepId());
            if (change != null) {
                update.setStatus(change.getStatus());
                update.setResultSummary(resultSummary(change));
            }
            updates.add(update);
        }
        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest();
        request.setExpectedVersion(plan.getVersion());
        request.setSteps(updates);
        return request;
    }

    // 不另存整段聊天；步骤只保留简短理由、真实引用和确认方式。
    private String resultSummary(StepProgressChange change) {
        String summary = "依据：" + basisLabel(change.getCompletionBasis()) + "；用户已批准本次进度变更。\n理由：" + change.getReason();
        if (!change.getUserEvidence().isBlank()) summary += "\n用户原话：" + change.getUserEvidence();
        if (change.getStatus() == AgentTaskStepStatus.COMPLETED) summary += "\n知识正确性与用户掌握程度尚未独立验证。";
        return summary;
    }

    // 只有当前计划、当前版本的引用能被解析，不信任模型提供的顺序数字或数据库主键。
    private AgentTaskStep resolve(AgentTaskPlan plan, String reference) {
        return plan.getSteps().stream().filter(step -> stepRef(plan, step).equals(reference)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("步骤引用已过期或不属于当前目标"));
    }

    // 返回全部最新步骤，提醒模型下一次申请复制新版本引用。
    private String resultJson(SessionGoalSnapshot snapshot) {
        AgentTaskPlan plan = snapshot.getCurrentPlan();
        var root = JSON.createObjectNode();
        root.put("currentGoalRef", "goal-" + plan.getGoalNumber());
        root.put("masteryVerified", false);
        var steps = root.putArray("steps");
        for (AgentTaskStep step : plan.getSteps()) {
            var node = steps.addObject();
            node.put("stepRef", stepRef(plan, step));
            node.put("description", step.getDescription());
            node.put("completionCriteria", step.getCompletionCriteria());
            node.put("status", step.getStatus().name());
            node.put("resultSummary", step.getResultSummary());
        }
        return root.toString();
    }

    // 依据类型用固定中文展示，不能让模型把“用户继续”包装成“知识已验证”。
    private String basisLabel(String basis) {
        return switch (basis) {
            case "DIALOGUE_EVIDENCE" -> "对话依据，由用户确认；未独立验证掌握";
            case "USER_CONFIRMED" -> "用户选择继续，未验证掌握";
            default -> "进度调整，不涉及掌握判定";
        };
    }

    // 保留引用原文，不做内容补写；长度边界同时用于避免超出数据库结果字段。
    private String readText(JsonNode node, String name, int limit, boolean allowEmpty) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asString().length() > limit || (!allowEmpty && value.asString().isBlank())) {
            throw new IllegalArgumentException("字段类型或长度不合法");
        }
        return value.asString();
    }

    // Schema 提示模型字段要求，真实输入仍经过上面的后端校验。
    private Map<String, Object> text(String description, int limit) {
        return Map.of("type", "string", "minLength", 1, "maxLength", limit, "description", description);
    }
}
