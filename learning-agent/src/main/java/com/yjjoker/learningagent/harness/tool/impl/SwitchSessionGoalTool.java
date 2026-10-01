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
public class SwitchSessionGoalTool implements Tool {
    private final SessionGoalToolService service;

    // 模型通过固定名称选择这个工具。
    @Override
    public String name() { return "switch_session_goal"; }

    // 描述使用时机，真正的归属和版本检查仍由后端执行。
    @Override
    public String description() { return "仅专注模式可用。用户明确要切换或回到已有目标时，原样复制当前索引的 goalRef。审批通过后切换并保留旧步骤；不清空、不取消旧目标。必须单独调用本工具。"; }

    // 索引和结果是本次请求快照，不作为未来请求的最新状态重放。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 复用现有 Hook 与检查点，不新增目标专属审批流程。
    @Override
    public boolean requiresUserApproval() { return true; }

    // 目标名称来自本轮服务端快照，不让模型伪造审批展示文字。
    @Override
    public String approvalReason(String input) { return service.switchApprovalReason(input); }

    // 切换方向前不能混入其他业务工具。
    @Override
    public boolean requiresExclusiveBatch() { return true; }

    // 预检只检查参数和快照，不修改数据库。
    @Override
    public ToolExecutionResult validateApprovalInput(String input) { return service.switchTo(input, false); }

    // 模型只提供业务参数，不允许它指定用户或会话。
    @Override
    public Map<String, Object> parametersSchema() { return service.switchSchema(); }

    // Harness 确认审批通过后才执行实际变更。
    @Override
    public ToolExecutionResult execute(String input) { return service.switchTo(input, true); }
}
