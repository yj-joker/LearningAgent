package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.context.OriginalToolResultStore;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

// 当工具结果被截断时，模型通过这个工具按片段读取原始结果，而不是一次性恢复全部内容。
// 它本身不关心结果来自内存还是数据库，只依赖 OriginalToolResultStore 的统一读取接口。
@Component
@AllArgsConstructor
public class GetOriginalToolResultTool implements Tool {

    // 工具参数由模型生成，因此仍使用项目统一的 Jackson 解析器读取 JSON 字符串。
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    // 不提供 limit 时读取 200 个字符，避免一次恢复过多内容。
    private static final int DEFAULT_LIMIT = 200;

    // 即使模型主动要求更多内容，也设置单次硬上限，防止恢复工具绕过上下文管理。
    private static final int MAX_LIMIT = 300;

    // Spring 注入的是接口，实际对象由 @Primary 的 DatabaseOriginalToolResultStore 提供。
    private final OriginalToolResultStore resultStore;

    @Override
    public String name() {
        return "get_original_tool_result";
    }

    @Override
    public String description() {
        return "当工具结果显示已截断且需要更多细节时，按 toolCallId 和 offset 读取原始结果片段。每次最多读取 300 个字符。";
    }

    @Override
    public boolean isContextRecoveryTool() {
        // Harness 据此应用恢复预算，并把本轮消息标记为不可在未来上下文中重放。
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        // Schema 是发给 LLM 的参数说明；它约束模型应该生成哪些字段和字段类型。
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "toolCallId", Map.of(
                                "type", "string",
                                "description", "被截断工具结果中的 toolCallId"
                        ),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "从原始结果第几个字符开始读取，第一次使用 0"
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "本次读取字符数，最大 300，省略时默认 200"
                        )
                ),
                "required", List.of("toolCallId"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolExecutionResult execute(String input) {
        try {
            // input 是模型生成的 JSON 文本，先解析再读取字段，不能直接当作 toolCallId 使用。
            JsonNode arguments = JSON_MAPPER.readTree(input);
            if (arguments == null || !arguments.isObject()) {
                return invalidArgument("参数必须是 JSON 对象");
            }

            JsonNode callIdNode = arguments.get("toolCallId");
            if (callIdNode == null || !callIdNode.isTextual() || callIdNode.asString().isBlank()) {
                return invalidArgument("toolCallId 必须是非空字符串");
            }

            int offset = readInteger(arguments.get("offset"), 0);
            int limit = readInteger(arguments.get("limit"), DEFAULT_LIMIT);
            if (offset < 0 || limit <= 0 || limit > MAX_LIMIT) {
                // 参数错误是模型可以修正的问题，因此返回 retryable=true，而不是抛异常终止循环。
                return invalidArgument("offset 不能小于 0，limit 必须在 1 到 300 之间");
            }

            String toolCallId = callIdNode.asString();
            // 先获取总长度，既能判断调用 ID 是否存在，也能让模型知道后续是否还有片段。
            int totalLength = resultStore.length(toolCallId);
            if (totalLength < 0) {
                return ToolExecutionResult.failure(
                        "ORIGINAL_TOOL_RESULT_NOT_FOUND",
                        "找不到对应的原始工具结果，可能已经超出当前 Agent 运行范围",
                        false
                );
            }

            // 只读取请求范围内的片段，避免恢复工具本身制造新的大消息。
            String content = resultStore.read(toolCallId, offset, limit);
            boolean hasMore = offset + content.length() < totalLength;
            // 把定位信息和 hasMore 一并返回，模型可以据此决定是否读取下一段。
            return ToolExecutionResult.success(
                    "toolCallId=" + toolCallId
                            + ", offset=" + offset
                            + ", totalLength=" + totalLength
                            + ", hasMore=" + hasMore
                            + "\n" + content
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            return invalidArgument("参数不是有效的 JSON，offset 和 limit 必须是整数");
        }
    }

    private int readInteger(JsonNode node, int defaultValue) {
        if (node == null) {
            // offset 和 limit 都是可选参数，缺省时使用安全默认值。
            return defaultValue;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("参数必须是整数");
        }
        return node.asInt();
    }

    private ToolExecutionResult invalidArgument(String message) {
        // 统一标记为可重试错误，让模型有机会修正 JSON 参数后再次调用。
        return ToolExecutionResult.failure("INVALID_ARGUMENT", message, true);
    }
}
