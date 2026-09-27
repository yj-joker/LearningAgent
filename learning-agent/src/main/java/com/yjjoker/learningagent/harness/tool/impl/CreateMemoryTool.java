package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.memory.model.MemoryOperation;
import com.yjjoker.learningagent.harness.memory.service.MemoryToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

// 工具入口只说明操作，参数、权限和事务统一交给记忆工具服务。
@Component
@RequiredArgsConstructor
public class CreateMemoryTool implements Tool {
    private final MemoryToolService service;

    // 模型通过这个名称选择 Java 工具。
    @Override
    public String name() { return "create_memory"; }

    // 明确本轮用户授权和目标选择规则。
    @Override
    public String description() { return "仅当本轮用户明确要求记住新事实时调用。USER 为跨会话记忆，SESSION 为当前会话记忆；已有事实请使用 update_memory。"; }

    // 让后置提取识别这是记忆写操作，而不是普通查询。
    @Override
    public boolean isMemoryWriteTool() { return true; }

    // 引用只属于当前请求，相关工具消息不能跨轮重放。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 模型看到参数规则，后端仍会逐项校验。
    @Override
    public Map<String, Object> parametersSchema() { return service.schema(MemoryOperation.CREATE); }

    // 操作类型由 Java 固定，不能让模型参数改成另一种操作。
    @Override
    public ToolExecutionResult execute(String input) { return service.write(MemoryOperation.CREATE, input); }
}

