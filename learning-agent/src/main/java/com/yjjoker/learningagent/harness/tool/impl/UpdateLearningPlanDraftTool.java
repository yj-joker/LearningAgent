package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.learningplan.service.LearningPlanDraftToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

// 修改草案的写工具；版本校验和用户审批都不能省略。
@Component
@RequiredArgsConstructor
public class UpdateLearningPlanDraftTool implements Tool {
    private final LearningPlanDraftToolService service;

    @Override
    public String name() { return "update_learning_plan_draft"; }

    // 修改已有草案时必须复制最新 draftRef、expectedVersion 和完整步骤列表。
    @Override
    public String description() {
        return "修改同一份学习计划草案。先读取最新草案，再提交 draftRef、expectedVersion 和完整步骤；需要用户审批，成功后版本递增但仍是 DRAFT。";
    }

    @Override
    public boolean requiresUserApproval() { return true; }

    @Override
    public boolean requiresExclusiveBatch() { return true; }

    @Override
    public boolean isContextScopedTool() { return true; }

    @Override
    public ToolExecutionResult validateApprovalInput(String input) { return service.validateUpdate(input); }

    @Override
    public String approvalReason(String input) { return service.updateApprovalReason(input); }

    @Override
    public Map<String, Object> parametersSchema() { return service.updateSchema(); }

    @Override
    public ToolExecutionResult execute(String input) { return service.update(input); }
}
