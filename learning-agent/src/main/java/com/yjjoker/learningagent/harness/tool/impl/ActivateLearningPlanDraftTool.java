package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.learningplan.service.LearningPlanDraftToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;

import java.util.Map;

// 确认草案正式生效的写工具；Agent 必须经过通用审批，不能靠自然语言改变状态。
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class ActivateLearningPlanDraftTool implements Tool {
    private final LearningPlanDraftToolService service;

    @Override
    public String name() {
        return "activate_learning_plan_draft";
    }

    // 用户明确确认正式生效时使用；批准前草案仍保持 DRAFT。
    @Override
    public String description() {
        return "确认同一份学习计划草案正式生效。必须先读取最新草案，提交 draftRef 和 expectedVersion；需要用户审批，成功后状态变为 ACTIVE、formal=true。";
    }

    @Override
    public boolean requiresUserApproval() {
        return true;
    }

    @Override
    public boolean requiresExclusiveBatch() {
        return true;
    }

    @Override
    public boolean isContextScopedTool() {
        return true;
    }

    @Override
    public ToolExecutionResult validateApprovalInput(String input) {
        return service.validateActivate(input);
    }

    @Override
    public String approvalReason(String input) {
        return service.activateApprovalReason(input);
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return service.activateSchema();
    }

    @Override
    public ToolExecutionResult execute(String input) {
        return service.activate(input);
    }
}
