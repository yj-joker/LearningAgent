package com.yjjoker.learningagent.harness.llm;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;

import java.util.List;

// 模型通信接口：屏蔽不同 LLM 厂商在地址、鉴权方式、请求体和响应体上的差异。
// Harness 只认识这个稳定接口，因此更换模型厂商时主要替换实现类，不需要重写 Harness 流程。
public interface LlmClient {

    // generate 表示“让模型根据完整消息列表生成下一步结果”，而不是保证生成最终聊天文本。
    // 模型的下一步既可能是 TextLlmResponse，也可能是 ToolCallLlmResponse，调用方必须区分处理。
    // 传入完整列表是因为工具执行后的第二次请求必须同时带上用户消息、工具请求和工具结果。
    LlmResponse generate(List<LlmMessage> messages);


    // 摘要模型等其他用处模型可能不需要工具调用，因此默认实现保持旧测试客户端兼容。
    default LlmResponse generateWithoutTools(List<LlmMessage> messages) {
        return generate(messages);
    }
}
