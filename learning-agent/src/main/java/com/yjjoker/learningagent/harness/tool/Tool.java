package com.yjjoker.learningagent.harness.tool;

import java.util.Map;

// Harness 中所有工具都必须遵守的统一接口。
// 对 Harness 来说，查询课程、搜索知识库和执行计算虽然内部逻辑不同，
// 但都可以统一理解成“根据名称找到工具，再把输入交给工具执行”。
public interface Tool {

    // 返回工具的唯一名称，例如 course_query。
    // 后续 LLM 请求调用工具时会携带这个名称，Harness 依靠它找到正确的 Java 实现。
    String name();

    // 返回给 LLM 阅读的工具说明，帮助模型判断这个工具适合解决什么问题。
    // 名称负责精确定位工具，说明负责表达工具的用途，两者承担的职责不同。
    String description();

    // 恢复类工具只为当前 Agent Loop 补充临时细节，其调用和结果不会进入未来上下文。
    default boolean isContextRecoveryTool() {
        return false;
    }

    // 返回工具输入参数的 JSON Schema，也就是“参数应该长什么样”的结构说明。
    // 模型不会读取 Java 方法签名，因此必须通过这个结构知道参数名称、类型以及哪些参数必填。
    // 当前默认结构表示工具不接收任何参数，find_all_users 正好可以直接使用这个默认实现。
    default Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
        );
    }

    // input 是模型按照 parametersSchema 生成的 JSON 字符串，例如 {"courseId":1001}。
    // 返回统一结果对象，让 Harness 能区分正常数据与模型可以修正的参数错误。
    ToolExecutionResult execute(String input);
}
