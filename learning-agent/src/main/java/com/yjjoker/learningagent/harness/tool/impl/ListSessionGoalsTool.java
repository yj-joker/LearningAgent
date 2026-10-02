package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.plan.service.SessionGoalToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 只读查询会话目标，不创建审批，不改变当前目标。
@Component
@RequiredArgsConstructor
public class ListSessionGoalsTool implements Tool {
    private final SessionGoalToolService service;

    // 模型通过固定名称选择这个工具。
    @Override
    public String name() { return "list_session_goals"; }

    // 描述使用时机，真正的归属和版本检查仍由后端执行。
    @Override
    public String description() { return "仅专注模式可用。查询当前会话的目标索引和当前目标引用；搁置目标保留原步骤，若要查看当前目标的具体步骤请调用 get_session_goal_progress。"; }

    // 索引和结果是本次请求快照，不作为未来请求的最新状态重放。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 返回当前会话的目标索引。
    @Override
    public ToolExecutionResult execute(String input) { return service.list(input); }
}
