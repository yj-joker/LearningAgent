package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.error.HarnessException;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

// 参数校验和工具响应集中在这里；事务服务专门负责版本检查与数据库写入。
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionGoalToolService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SessionGoalContext context;
    private final SessionGoalService goals;

    // 新增目标直接携带待审批的短计划，批准后不再生成另一份用户没看过的计划。
    public Map<String, Object> createSchema() {
        Map<String, Object> step = Map.of("type", "object", "properties", Map.of(
                "description", text("执行步骤", 200), "completionCriteria", text("完成条件", 200)),
                "required", List.of("description", "completionCriteria"), "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.of(
                "goal", text("本轮用户希望新增的目标；已有目标应切换而不是重复创建", 500),
                "constraints", Map.of("type", "string", "maxLength", 500),
                "steps", Map.of("type", "array", "minItems", 1, "maxItems", 3, "items", step)),
                "required", List.of("goal", "steps"), "additionalProperties", false);
    }

    // 切换只接受索引中已有引用，不让模型指定用户、会话或数据库主键。
    public Map<String, Object> switchSchema() {
        return Map.of("type", "object", "properties", Map.of("goalRef", text("原样复制当前会话的 goal-N 引用", 32)),
                "required", List.of("goalRef"), "additionalProperties", false);
    }

    // 查询也校验模式和归属；刷新索引后模型才会看到新增目标。
    public ToolExecutionResult list(String input) {
        return process("LIST", input, false);
    }

    // 审批前只校验，execute=true 时才调用事务保存；不会把预检通过说成写入成功。
    public ToolExecutionResult create(String input, boolean execute) {
        return process("CREATE", input, execute);
    }

    // 切换回来与切到其他目标共用一个入口，原步骤不会重新创建。
    public ToolExecutionResult switchTo(String input, boolean execute) {
        return process("SWITCH", input, execute);
    }

    // 审批页面显示从哪个目标切到哪个目标，避免用户只看到无法理解的短编号。
    public String switchApprovalReason(String input) {
        SessionGoalSnapshot snapshot = context.require();
        var target = snapshot.resolve(object(input).path("goalRef").asString());
        return "将当前目标「" + shortTitle(snapshot.getCurrentPlan().getGoal()) + "」切换为「"
                + shortTitle(target.getGoal()) + "」（goal-" + target.getGoalNumber() + "）。原目标及步骤保留，可稍后恢复。";
    }

    // 审批说明列最多 500 字；截短显示标题，不改变数据库里的完整目标。
    private String shortTitle(String title) {
        int length = title.codePointCount(0, title.length());
        return length <= 120 ? title : title.substring(0, title.offsetByCodePoints(0, 120)) + "…";
    }

    // 将可修正参数错误交回模型；系统异常仍由 Harness 统一处理，不伪装成功。
    private ToolExecutionResult process(String operation, String input, boolean execute) {
        try {
            SessionGoalSnapshot expected = context.require();
            goals.requireUnchanged(expected);
            SessionGoalSnapshot updated;
            if ("CREATE".equals(operation)) {
                var request = FocusPlanPlanner.parsePlan(input);
                if (expected.getGoals().size() >= SessionGoalService.MAX_GOALS) {
                    return ToolExecutionResult.failure("GOAL_LIMIT", "会话目标已达 20 个，请另开会话", false);
                }
                if (expected.getGoals().stream().anyMatch(goal -> goal.getGoal().equals(request.getGoal()))) {
                    throw new IllegalArgumentException("同名目标已存在，请改用 switch_session_goal");
                }
                if (!execute) return ToolExecutionResult.success("新增目标参数已校验，尚未保存");
                updated = goals.create(expected, request);
            } else if ("SWITCH".equals(operation)) {
                JsonNode root = object(input);
                if (!root.propertyNames().equals(Set.of("goalRef")) || !root.path("goalRef").isTextual()
                        || root.path("goalRef").asString().length() > 32) {
                    throw new IllegalArgumentException("必须仅提供 goalRef");
                }
                String ref = root.path("goalRef").asString();
                goals.validateTarget(expected, ref);
                if (!execute) return ToolExecutionResult.success("切换目标参数已校验，尚未切换");
                updated = goals.switchTo(expected, ref);
            } else {
                if (!object(input).isEmpty()) throw new IllegalArgumentException("查询不接收参数");
                updated = goals.load(expected.getState().getSessionId());
            }
            // 事务正常返回才替换本轮快照；Harness 随后刷新模型系统消息。
            context.bind(updated);
            log.info("会话目标工具完成，operation={}，sessionId={}，focusVersion={}，currentGoalNumber={}",
                    operation, updated.getState().getSessionId(), updated.getState().getVersion(),
                    updated.getCurrentPlan().getGoalNumber());
            return ToolExecutionResult.success(indexJson(updated));
        } catch (SecurityException exception) {
            return ToolExecutionResult.failure("GOAL_ACCESS_DENIED", "目标工具仅供当前用户的有效专注会话使用", false);
        } catch (ClientDataErrorException exception) {
            log.warn("目标工具拒绝旧版本或无效目标，operation={}", operation);
            return ToolExecutionResult.failure("GOAL_CHANGED", "目标已变化或无法切换，请重新读取并确认后申请审批", true);
        } catch (HarnessException | JacksonException | IllegalArgumentException exception) {
            log.warn("目标工具参数校验失败，operation={}，errorType={}", operation, exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("INVALID_GOAL_ARGUMENT", "请按工具结构填写短计划，或复制当前索引中非当前目标的 goalRef", true);
        }
    }

    // 索引只发引用与标题，避免把全部搁置目标的步骤重复塞入上下文。
    private String indexJson(SessionGoalSnapshot snapshot) {
        var root = JSON.createObjectNode();
        root.put("currentGoalRef", "goal-" + snapshot.getCurrentPlan().getGoalNumber());
        var entries = root.putArray("goals");
        for (var goal : snapshot.getGoals()) {
            var node = entries.addObject();
            node.put("goalRef", "goal-" + goal.getGoalNumber());
            node.put("goal", goal.getGoal());
            node.put("current", goal.getPlanId().equals(snapshot.getState().getActivePlanId()));
        }
        return root.toString();
    }

    // 严格读取一个小 JSON 对象，拒绝重复字段、尾随内容和巨量输入。
    private JsonNode object(String input) {
        if (input == null || input.length() > 6000) throw new IllegalArgumentException("参数长度不合法");
        JsonNode root = JSON.readTree(input);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("参数必须是对象");
        return root;
    }

    // Schema 给模型提示，后端仍通过解析器检查真实输入。
    private Map<String, Object> text(String description, int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum, "description", description);
    }
}
