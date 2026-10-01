package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 通用审批策略：工具声明需要确认，Hook 提出要求，Harness 负责暂停和续接。
@Component
@RequiredArgsConstructor
public class ToolApprovalHook implements AgentHook {
    private final ToolRegistry tools;

    // 先做无副作用的业务校验，参数错误交回模型，不创建无效审批。
    @Override
    public ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall call) {
        var tool = tools.getRequiredTool(call.name());
        if (!tool.requiresUserApproval()) {
            return ToolCallHookResult.allow();
        }
        var validation = tool.validateApprovalInput(call.arguments());
        if (!validation.isSuccess()) {
            return ToolCallHookResult.reject(validation.error());
        }
        // 业务工具可展示目标名称；是否暂停与恢复仍由 Harness 统一决定。
        return ToolCallHookResult.requireApproval(tool.approvalReason(call.arguments()));
    }
}
