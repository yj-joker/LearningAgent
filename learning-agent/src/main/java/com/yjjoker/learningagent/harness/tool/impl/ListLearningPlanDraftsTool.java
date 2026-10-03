package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.learningplan.service.LearningPlanDraftToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 读取当前用户的草案索引；只读工具不需要用户审批。
@Component
@RequiredArgsConstructor
public class ListLearningPlanDraftsTool implements Tool {
    private final LearningPlanDraftToolService service;

    // 模型通过这个名称读取草案列表。
    @Override
    public String name() { return "list_learning_plan_drafts"; }

    // 讨论已有学习计划时先读取数据库索引，不依赖上下文中的旧草案。
    @Override
    public String description() {
        return "读取当前用户保存的学习计划草案索引和版本。草案状态始终是 DRAFT，不能把它说成正式生效计划；需要完整步骤时再用 get_learning_plan_draft。";
    }

    // 草案列表可能在下一次请求发生变化，工具消息不作为未来历史直接重放。
    @Override
    public boolean isContextScopedTool() { return true; }

    @Override
    public ToolExecutionResult execute(String input) { return service.list(); }
}
