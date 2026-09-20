package com.yjjoker.learningagent.harness.llm.model;

// LlmResponse 是一次模型调用结果的统一类型。
// sealed 表示目前只允许下面列出的两种实现，避免调用方遗漏某种无法预料的结果类型。
// 它本身不保存字段，真正的数据分别保存在文本结果和工具调用结果中。
public sealed interface LlmResponse permits TextLlmResponse, ToolCallLlmResponse {
}
