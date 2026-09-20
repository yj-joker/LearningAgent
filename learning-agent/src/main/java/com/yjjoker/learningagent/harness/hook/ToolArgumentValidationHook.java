package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 在工具真正执行前，统一检查模型生成的参数外层格式。
// 每个字段的业务规则仍由具体工具负责，例如 username 是否为空、courseId 是否存在。
@Component
// 参数校验优先运行。它拒绝调用后，后面的日志等工具前置 Hook 不会继续执行。
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ToolArgumentValidationHook implements AgentHook {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    // 限制模型一次生成的工具参数长度，避免异常大参数继续占用内存并进入业务代码。
    private static final int MAX_ARGUMENT_LENGTH = 10_000;

    @Override
    public ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall toolCall) {
        String arguments = toolCall.arguments();

        // 即使工具没有参数，模型也应该返回空 JSON 对象 {}，而不是 null 或空字符串。
        if (arguments == null || arguments.isBlank()) {
            return ToolCallHookResult.reject(
                    "INVALID_TOOL_ARGUMENTS",
                    "工具调用参数不能为空，请按照工具参数要求重新生成",
                    true
            );
        }

        if (arguments.length() > MAX_ARGUMENT_LENGTH) {
            return ToolCallHookResult.reject(
                    "TOOL_ARGUMENTS_TOO_LARGE",
                    "工具调用参数过大，无法继续执行",
                    false
            );
        }

        try {
            JsonNode argumentsNode = JSON_MAPPER.readTree(arguments);

            // 当前所有工具的参数 Schema 顶层都是 object，因此数组、字符串和数字都不能直接作为参数。
            if (argumentsNode == null || !argumentsNode.isObject()) {
                return ToolCallHookResult.reject(
                        "INVALID_TOOL_ARGUMENTS",
                        "工具调用参数必须是 JSON 对象，请按照工具参数要求重新生成",
                        true
                );
            }
        } catch (JacksonException exception) {
            // 不把 Jackson 的详细解析错误交给模型，避免泄露内部实现并保持错误信息稳定。
            return ToolCallHookResult.reject(
                    "INVALID_TOOL_ARGUMENTS",
                    "工具调用参数不是合法的 JSON，请按照工具参数要求重新生成",
                    true
            );
        }

        return ToolCallHookResult.allow();
    }
}
