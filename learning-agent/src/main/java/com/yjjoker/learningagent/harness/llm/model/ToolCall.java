package com.yjjoker.learningagent.harness.llm.model;


// ToolCall 表示模型提出的一次工具调用要求，它本身不会执行任何 Java 代码。
// id 用来把后面的工具结果与本次调用对应起来，name 用来查找工具，arguments 是模型生成的 JSON 参数。
public record ToolCall(String id, String name, String arguments) {

}
