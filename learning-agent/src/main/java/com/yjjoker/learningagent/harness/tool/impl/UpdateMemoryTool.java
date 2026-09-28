package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.memory.model.MemoryOperation;
import com.yjjoker.learningagent.harness.memory.service.MemoryToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

// 审批 Hook 先检查参数，Harness 确认批准后才调用 execute 执行记忆写入。
@Component
@RequiredArgsConstructor
public class UpdateMemoryTool implements Tool {
    private final MemoryToolService service;

    // 模型通过这个名称选择 Java 工具。
    @Override
    public String name() { return "update_memory"; }

    // 明确本轮用户授权和目标选择规则。
    @Override
    public String description() { return "仅当本轮用户明确要求修改已有记忆时调用。使用当前索引的 targetMemoryRefs，一次选中全部同义目标；先用 list_memories 刷新不确定的目标。"; }

    // 让后置提取识别这是记忆写操作，而不是普通查询。
    @Override
    public boolean isMemoryWriteTool() { return true; }

    // 交给通用审批 Hook；主循环不需要判断这是不是记忆工具。
    @Override
    public boolean requiresUserApproval() { return true; }

    // 审批前仅校验，不修改数据库；正式执行时还会核对旧目标版本。
    @Override
    public ToolExecutionResult validateApprovalInput(String input) {
        return service.validate(MemoryOperation.UPDATE, input);
    }

    // 引用只属于当前请求，相关工具消息不能跨轮重放。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 模型看到参数规则，后端仍会逐项校验。
    @Override
    public Map<String, Object> parametersSchema() { return service.schema(MemoryOperation.UPDATE); }

    // 固定操作类型；批准后的实际写入复用记忆事务服务。
    @Override
    public ToolExecutionResult execute(String input) { return service.write(MemoryOperation.UPDATE, input); }
}
