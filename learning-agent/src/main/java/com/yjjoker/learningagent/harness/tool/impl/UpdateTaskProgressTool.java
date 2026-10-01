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
        return "仅专注模式：申请更新当前目标的步骤进度。复制最新 stepRef，可在同一申请中完成当前步骤并开始下一步。"
                + "开始用 IN_PROGRESS；完成用 COMPLETED，填写理由，按对话依据 DIALOGUE_EVIDENCE 或用户主动继续 USER_CONFIRMED 区分；"
                + "对话依据须原样引用用户原话 userEvidence，不可用助手讲完代替用户掌握。其他状态 completionBasis 用 NOT_APPLICABLE。"
                + "用户选择继续不需要答题证明，审批通过即可按用户确认记录；不表示掌握已验证。"
                + "PENDING 不能直接完成，先申请开始。受阻用 BLOCKED，取消用 CANCELED。必须单独调用，批准并执行成功后才算更新。";
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
