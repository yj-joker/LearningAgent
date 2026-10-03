package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.learningplan.service.LearningPlanDraftToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

// 创建草案的写工具；预检和正式执行都由统一审批流程控制。
@Component
@RequiredArgsConstructor
public class CreateLearningPlanDraftTool implements Tool {
    private final LearningPlanDraftToolService service;

    @Override
    public String name() { return "create_learning_plan_draft"; }

    // 用户明确要求保存草案时使用；讨论本身不触发写入。
    @Override
    public String description() {
        return "保存一份学习计划草案。只有用户明确要求保存或创建草案时使用；必须先确认目标、基础、投入时间和限制。执行需要用户审批，成功后状态仍为 DRAFT。";
    }

    @Override
    public boolean requiresUserApproval() { return true; }

    @Override
    public boolean requiresExclusiveBatch() { return true; }

    @Override
    public boolean isContextScopedTool() { return true; }

    @Override
    public ToolExecutionResult validateApprovalInput(String input) { return service.validateCreate(input); }

    @Override
    public String approvalReason(String input) { return service.createApprovalReason(input); }

    @Override
    public Map<String, Object> parametersSchema() { return service.createSchema(); }

    @Override
    public ToolExecutionResult execute(String input) { return service.create(input); }
}
