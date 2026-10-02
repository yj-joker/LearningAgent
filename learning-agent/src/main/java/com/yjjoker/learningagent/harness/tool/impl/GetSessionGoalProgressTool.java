package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.plan.service.SessionGoalToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 只读返回当前目标的完整步骤，正式状态来自数据库快照而不是模型历史。
@Component
@RequiredArgsConstructor
public class GetSessionGoalProgressTool implements Tool {
    private final SessionGoalToolService service;

    // 模型通过固定名称调用当前进度查询。
    @Override
    public String name() { return "get_session_goal_progress"; }

    // 明确查询工具不会创建审批或修改步骤。
    @Override
    public String description() {
        return "仅专注模式可用。读取当前目标、计划版本、全部步骤、完成条件和数据库真实状态；只读，不修改进度，不创建审批。";
    }

    // 查询结果只属于当前 AgentLoop，不直接重放到未来历史。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 查询不接收参数，使用 Tool 默认的空对象 Schema。
    @Override
    public ToolExecutionResult execute(String input) { return service.progress(input); }
}
