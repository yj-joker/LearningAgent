package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.plan.service.SessionGoalToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

// 通过通用审批提出目标变更，批准之后才进入事务写入。
@Component
@RequiredArgsConstructor
public class CreateSessionGoalTool implements Tool {
    private final SessionGoalToolService service;

    // 模型通过固定名称选择这个工具。
    @Override
    public String name() { return "create_session_goal"; }

    // 描述使用时机，真正的归属和版本检查仍由后端执行。
    @Override
    public String description() { return "仅专注模式可用。用户转向新目标或继续学习下一长期阶段时，提出新的 1 到 3 步短计划；有关联长期计划时填写 learningScope 和 learningPlanStageRef。用户审批通过后新增并切换，不改绑原任务。已有目标使用 switch_session_goal；普通追问不要新增，含糊时先询问。必须单独调用本工具。"; }

    // 索引和结果是本次请求快照，不作为未来请求的最新状态重放。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 复用现有 Hook 与检查点，不新增目标专属审批流程。
    @Override
    public boolean requiresUserApproval() { return true; }

    // 新目标和具体步骤由审批面板的原始参数展示，批准后按同一份参数保存。
    @Override
    public String approvalReason(String input) { return "新增并切换到下面的目标和短计划；原目标及步骤保留，可稍后恢复。"; }

    // 切换方向前不能混入其他业务工具。
    @Override
    public boolean requiresExclusiveBatch() { return true; }

    // 预检只检查参数和快照，不修改数据库。
    @Override
    public ToolExecutionResult validateApprovalInput(String input) { return service.create(input, false); }

    // 模型只提供业务参数，不允许它指定用户或会话。
    @Override
    public Map<String, Object> parametersSchema() { return service.createSchema(); }

    // Harness 确认审批通过后才执行实际变更。
    @Override
    public ToolExecutionResult execute(String input) { return service.create(input, true); }
}
