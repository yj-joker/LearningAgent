package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.plan.service.TaskPlanToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

// 通过通用审批申请修改当前目标的步骤内容、顺序或取消状态。
@Component
@RequiredArgsConstructor
public class UpdateTaskPlanTool implements Tool {
    private final TaskPlanToolService service;

    // 返回模型请求工具时使用的固定名称。
    @Override
    public String name() { return "update_task_plan"; }

    // 明确计划结构变化必须走工具，不能只用自然语言宣称已经修改。
    @Override
    public String description() {
        return "仅专注模式可用。用户明确要求增加、修改、取消或调整步骤时，复制最新计划的 expectedVersion 和全部 stepRef，"
                + "提交完整步骤列表；新增步骤 stepRef 填 null，取消步骤使用 CANCELED。必须单独调用，用户审批通过后才会生成新计划版本。";
    }

    // 计划结构变化依赖当前会话和当前计划版本，不能脱离本轮快照重放。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 计划修改会影响后续执行方向，必须由用户确认。
    @Override
    public boolean requiresUserApproval() { return true; }

    // 结构变化不能和旧计划版本的其他工具调用混在同一批中。
    @Override
    public boolean requiresExclusiveBatch() { return true; }

    // 审批前只解析和校验，不改变计划版本或步骤内容。
    @Override
    public ToolExecutionResult validateApprovalInput(String input) { return service.update(input, false); }

    // 审批页面显示简短变更说明，完整参数由通用检查点保存。
    @Override
    public String approvalReason(String input) { return service.approvalReason(input); }

    // 让模型知道计划修改需要哪些字段，真实输入仍由后端再次校验。
    @Override
    public Map<String, Object> parametersSchema() { return service.schema(); }

    // 用户批准后由 Harness 调用正式事务更新。
    @Override
    public ToolExecutionResult execute(String input) { return service.update(input, true); }
}
