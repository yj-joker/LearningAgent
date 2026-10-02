package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.plan.service.TaskProgressToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

// 复用通用审批更新当前目标的步骤，不新增进度专属审批表或独立 AgentLoop。
@Component
@RequiredArgsConstructor
public class UpdateTaskProgressTool implements Tool {
    private final TaskProgressToolService service;

    // 提供给模型的固定工具名。
    @Override
    public String name() { return "update_task_progress"; }

    // 说明使用时机和依据；所有变更仍要用户审批，不依赖提示词保护数据库。
    @Override
    public String description() {
        // 状态与依据的配对规则由参数说明解释，这里只保留用途和申请边界。
        return "仅专注模式：申请更新当前目标的步骤进度。复制最新 stepRef，可在同一申请中完成当前步骤并开始下一步。"
                + "完成申请需要用户对话依据或明确通过、继续意愿；助手讲完不是完成证据，用户选择继续不强制答题，也不代表掌握已验证。"
                + "必须单独调用，批准并执行成功后才算更新。";
    }

    // 所有状态写入先经过现有审批 Hook。
    @Override
    public boolean requiresUserApproval() { return true; }

    // 同一次状态工具可批量改步骤，但不能与另一工具混用旧计划版本。
    @Override
    public boolean requiresExclusiveBatch() { return true; }

    // 工具结果只是本轮快照，后续以数据库重新组装的计划为准。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 暂停前校验状态和引用，不提前落库。
    @Override
    public ToolExecutionResult validateApprovalInput(String input) { return service.update(input, false); }

    // 向用户展示真实步骤、条件和模型提出的理由。
    @Override
    public String approvalReason(String input) { return service.approvalReason(input); }

    // 模型只提交变更列表，用户身份和计划版本由后端取得。
    @Override
    public Map<String, Object> parametersSchema() { return service.schema(); }

    // 由 Harness 在用户批准后执行，失败时不会宣称更新成功。
    @Override
    public ToolExecutionResult execute(String input) { return service.update(input, true); }
}
