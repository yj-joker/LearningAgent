package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.learningplan.service.CurrentLearningPlanToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 读取当前专注会话绑定的最新 ACTIVE 学习计划和阶段进度；只读工具不需要审批。
@Component
@RequiredArgsConstructor
public class GetCurrentLearningPlanTool implements Tool {
    private final CurrentLearningPlanToolService service;

    // 工具名由 ToolRegistry 自动注册并提供给模型。
    @Override
    public String name() {
        return "get_current_learning_plan";
    }

    // 工具只读取当前会话绑定计划，不允许模型指定任意 draftRef。
    @Override
    public String description() {
        return "仅专注模式可用。读取当前会话绑定的最新 ACTIVE 学习计划、计划版本、全部学习阶段和阶段真实进度；只读，不修改计划，不创建审批。";
    }

    // 当前计划查询依赖本轮专注会话上下文，结果只在本轮使用。
    @Override
    public boolean isContextScopedTool() {
        return true;
    }

    // 当前计划查询不接收参数，避免模型传入错误的计划引用。
    @Override
    public ToolExecutionResult execute(String input) {
        return service.get();
    }
}
