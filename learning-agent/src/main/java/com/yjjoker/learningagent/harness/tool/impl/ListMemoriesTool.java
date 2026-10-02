package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.memory.service.MemoryToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 刷新两类记忆索引；只读操作不会进入后置提取的写入记录。
@Component
@RequiredArgsConstructor
public class ListMemoriesTool implements Tool {
    private final MemoryToolService service;

    // 模型用这个名称刷新当前可见记忆。
    @Override
    public String name() { return "list_memories"; }

    // 列表只返回索引，正文仍需 recall_memory 读取。
    @Override
    public String description() {
        // 新索引替代旧快照，避免模型继续使用已删除目标或旧引用。
        return "查询并刷新当前用户和会话的全部有效记忆索引。返回 memoryRef、范围、key、主题和摘要，不返回正文。"
                + "用户要查看全部记忆、目标不明确或已变化时使用；以最新返回为准，缺少正文细节时可用 recall_memory。";
    }

    // 列表中的短引用不能在下一次请求中直接复用。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 不接收用户 ID 或会话 ID，由后端当前请求确定查询范围。
    @Override
    public ToolExecutionResult execute(String input) { return service.list(input); }
}
