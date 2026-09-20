package com.yjjoker.learningagent.harness.llm.model;


// 这种结果表示模型已经给出了可以直接返回给用户的最终文本。
public record TextLlmResponse(String content) implements LlmResponse {

}
