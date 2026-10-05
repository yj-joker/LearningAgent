package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;

import java.util.List;

// 只用后端保存的工具轨迹和数据库进度生成保护回答，不采用模型草稿里的完成声明。
public final class BackendFactAnswerBuilder {
    private static final int MAX_VISIBLE_TOOLS = 12;
    private static final int MAX_VISIBLE_STEPS = 12;
    private static final int MAX_FACT_TEXT = 200;

    private BackendFactAnswerBuilder() {
    }

    // 给出保护原因，并列出已确认的工具状态和当前阶段状态。
    public static String build(AgentRunContext context, SessionGoalProgress progress, String reason) {
        StringBuilder answer = new StringBuilder("本轮未能核实模型的最终回答，因此不会展示未经确认的结论。");
        if (reason != null && !reason.isBlank()) {
            answer.append("原因：").append(limit(reason, MAX_FACT_TEXT)).append("。");
        }
        appendToolFacts(answer, context == null ? List.of() : context.getToolExecutions());
        appendProgressFacts(answer, progress);
        if ((context == null || context.getToolExecutions().isEmpty()) && !hasSteps(progress)) {
            answer.append("本轮没有可展示的工具执行记录或专注进度快照。");
        }
        answer.append("以上状态来自后端记录；未列出的操作不能视为已执行。");
        return answer.toString();
    }

    // 按调用顺序列工具状态，不展示工具参数或结果正文。
    private static void appendToolFacts(StringBuilder answer, List<ToolExecutionRecord> records) {
        if (records.isEmpty()) {
            answer.append("本轮没有工具调用记录。");
            return;
        }
        answer.append("工具执行记录：");
        int visibleCount = Math.min(records.size(), MAX_VISIBLE_TOOLS);
        for (int index = 0; index < visibleCount; index++) {
            ToolExecutionRecord record = records.get(index);
            answer.append(limit(record.getToolName(), 80)).append("：")
                    .append(statusText(record.getStatus()));
            if (record.getApprovalDecision() != null) {
                answer.append("，用户审批 ").append(record.getApprovalDecision());
            }
            if (record.getResult() != null && record.getResult().getErrorCode() != null) {
                answer.append("，错误码 ").append(limit(record.getResult().getErrorCode(), 80));
            }
            answer.append("；");
        }
        if (records.size() > visibleCount) {
            answer.append("另有 ").append(records.size() - visibleCount).append(" 条工具记录未展开；");
        }
    }

    // 把步骤描述和数据库状态一起展示，防止只说“尚未完成”却没有可核对的进度。
    private static void appendProgressFacts(StringBuilder answer, SessionGoalProgress progress) {
        if (!hasSteps(progress)) {
            return;
        }
        answer.append("当前专注目标：").append(limit(progress.getGoal(), MAX_FACT_TEXT))
                .append("；计划版本：").append(progress.getPlanVersion()).append("。步骤状态：");
        int visibleCount = Math.min(progress.getSteps().size(), MAX_VISIBLE_STEPS);
        for (int index = 0; index < visibleCount; index++) {
            var step = progress.getSteps().get(index);
            answer.append("第").append(step.getPosition()).append("步“")
                    .append(limit(step.getDescription(), MAX_FACT_TEXT)).append("”是 ")
                    .append(step.getStatus()).append("；");
        }
        if (progress.getSteps().size() > visibleCount) {
            answer.append("另有 ").append(progress.getSteps().size() - visibleCount).append(" 个步骤未展开；");
        }
    }

    // 判断当前是否有可展示的步骤进度。
    private static boolean hasSteps(SessionGoalProgress progress) {
        return progress != null && progress.getSteps() != null && !progress.getSteps().isEmpty();
    }

    // 把工具状态转换成用户可理解的事实，不把请求状态误说成执行成功。
    private static String statusText(ToolExecutionRecord.Status status) {
        return switch (status) {
            case REQUESTED -> "已请求，尚未执行";
            case STARTED -> "执行中，结果未确认";
            case SUCCEEDED -> "执行成功";
            case FAILED -> "执行失败";
            case VALIDATION_FAILED -> "参数校验未通过，未执行";
            case REJECTED -> "已拒绝，未执行";
            case ERROR -> "发生异常，结果未确认";
        };
    }

    // 限制模型或业务文本长度，避免保护回答因长描述再次撑满上下文。
    private static String limit(String value, int maximum) {
        if (value == null || value.isBlank()) {
            return "未提供";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }
}
