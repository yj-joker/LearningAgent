package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.learningplan.dto.LearningProgressProposal;
import com.yjjoker.learningagent.harness.learningplan.service.LearningProgressProposalService;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalContext;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

// 长期学习掌握工具只提出证据和状态建议，正式写入必须经过通用用户审批。
@Component
@RequiredArgsConstructor
public class ProposeLearningProgressTool implements Tool {
    private final LearningProgressProposalService service;
    private final SessionGoalContext context;

    // 提供给模型的稳定工具名称；工具注册表会自动把它加入模型能力目录。
    @Override
    public String name() {
        return "propose_learning_progress";
    }

    // 说明工具边界；模型可以提出建议，但不能把建议说成已经确认。
    @Override
    public String description() {
        return "为当前 ACTIVE 学习计划步骤提交掌握进度建议。必须有用户解释或独立练习的真实证据；"
                + "CONFIRMED 必须同时具备两类证据。需要用户审批，批准并执行成功后才会更新长期进度。";
    }

    // 长期学习状态属于用户数据，任何写入都必须让用户确认。
    @Override
    public boolean requiresUserApproval() {
        return true;
    }

    // 进度申请要独占本批，避免同一轮同时提交互相冲突的长期状态。
    @Override
    public boolean requiresExclusiveBatch() {
        return true;
    }

    // 申请携带本轮计划和进度版本，不能作为未来历史消息直接重放。
    @Override
    public boolean isContextScopedTool() {
        return true;
    }

    // 审批前解析参数并重新核对本轮用户证据，不写数据库。
    @Override
    public ToolExecutionResult validateApprovalInput(String input) {
        try {
            LearningProgressProposal proposal = service.parse(input);
            AgentTaskPlan currentPlan = context.require().getCurrentPlan();
            return service.validate(proposal, context.currentUserMessage(),
                    context.require().getState().getSessionId(),
                    context.getLearningPlan() == null ? null : context.getLearningPlan().getDraftRef(),
                    context.getLearningPlanProgress(), currentPlan.getLearningPlanStageRef(),
                    currentPlan.getLearningPlanScope(), false);
        } catch (RuntimeException exception) {
            return ToolExecutionResult.failure("INVALID_LEARNING_PROGRESS",
                    exception.getMessage() == null ? "长期学习进度申请参数不合法" : exception.getMessage(), true);
        }
    }

    // 审批卡展示当前步骤、目标状态和模型理由，用户据此决定是否确认。
    @Override
    public String approvalReason(String input) {
        LearningProgressProposal proposal = service.parse(input);
        LearningPlanProgressVO snapshot = context.getLearningPlanProgress();
        if (snapshot == null) throw new IllegalStateException("当前没有长期学习计划进度");
        return service.approvalReason(proposal, snapshot);
    }

    // 返回严格 JSON Schema，让模型知道证据、版本和状态字段的边界。
    @Override
    public Map<String, Object> parametersSchema() {
        return service.schema();
    }

    // 用户批准后重新执行同一申请；服务会锁定最新计划和进度行并再次校验版本。
    @Override
    public ToolExecutionResult execute(String input) {
        try {
            LearningProgressProposal proposal = service.parse(input);
            AgentTaskPlan currentPlan = context.require().getCurrentPlan();
            ToolExecutionResult result = service.validate(proposal, context.currentUserMessage(),
                    context.require().getState().getSessionId(),
                    context.getLearningPlan() == null ? null : context.getLearningPlan().getDraftRef(),
                    context.getLearningPlanProgress(), currentPlan.getLearningPlanStageRef(),
                    currentPlan.getLearningPlanScope(), true);
            if (result.isSuccess()) {
                // 只有事务提交后才刷新本轮只读快照，后续模型能看到新的 CONFIRMED 状态。
                LearningPlanProgressVO updated = service.current(proposal.getDraftRef());
                context.bindLearningPlanProgress(updated);
            }
            return result;
        } catch (RuntimeException exception) {
            return ToolExecutionResult.failure("LEARNING_PROGRESS_WRITE_FAILED",
                    exception.getMessage() == null ? "保存长期学习进度失败" : exception.getMessage(), false);
        }
    }
}
