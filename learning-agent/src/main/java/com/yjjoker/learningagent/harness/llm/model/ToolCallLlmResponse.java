package com.yjjoker.learningagent.harness.llm.model;

import java.util.List;

// 这种结果表示模型暂时不能给出最终答案，希望应用先执行一个或多个工具。
// 一次模型响应可能并行请求多个工具，所以这里保存 List，而不是只保存单个 ToolCall。
public record ToolCallLlmResponse(List<ToolCall> toolCalls) implements LlmResponse {

    public ToolCallLlmResponse(List<ToolCall> toolCalls) {
        // 创建只读副本，避免外部代码在响应创建后继续修改列表，导致 Harness 执行内容发生变化。
        this.toolCalls = List.copyOf(toolCalls);
    }

}
