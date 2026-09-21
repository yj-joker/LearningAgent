package com.yjjoker.learningagent.harness.context;

import com.yjjoker.learningagent.config.HarnessContextProperties;
import com.yjjoker.learningagent.exception.ContextWindowExceededException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

// ContextManager 只管理发送给模型的工作上下文，不负责删除数据库中的原始历史。
// 当前阶段只做字符数估算和工具结果截断，摘要和长期记忆留到后续阶段。
@Component
public class ContextManager {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();
    private static final String TRUNCATED_MARKER = "\n[工具结果已截断，原始结果未删除]";

    private final int maxContextCharacters;
    private final int maxToolResultCharacters;

    // 存在测试专用构造方法，因此显式告诉 Spring 生产环境应使用配置对象创建组件。
    @Autowired
    public ContextManager(HarnessContextProperties properties) {
        this(properties.getMaxContextCharacters(), properties.getMaxToolResultCharacters());
    }

    // 测试可以直接传入较小阈值，验证压缩行为而不需要修改 Spring 配置。
    public ContextManager(int maxContextCharacters, int maxToolResultCharacters) {
        if (maxContextCharacters <= 0 || maxToolResultCharacters <= 0) {
            throw new IllegalArgumentException("上下文限制必须大于 0");
        }
        this.maxContextCharacters = maxContextCharacters;
        this.maxToolResultCharacters = maxToolResultCharacters;
    }

    // 每次请求模型前调用；如果上下文未超限，只复制列表，不改变消息内容。
    public List<LlmMessage> prepareForLlmRequest(List<LlmMessage> messages) {
        return fitToolResults(messages);
    }

    // 工具结果加入工作上下文后调用，与请求前使用同一套规则，避免两个入口行为不一致。
    public List<LlmMessage> compactAfterToolExecution(List<LlmMessage> messages) {
        return fitToolResults(messages);
    }

    // 用于测试和日志的近似大小；这里统计消息元数据和正文字符，不声称等于真实 token 数。
    public int estimateCharacters(List<LlmMessage> messages) {
        int total = 0;
        for (LlmMessage message : messages) {
            total += safeLength(message.getRole());
            total += safeLength(message.getContent());
            total += safeLength(message.getToolCallId());
            for (ToolCall toolCall : message.getToolCalls()) {
                total += safeLength(toolCall.id());
                total += safeLength(toolCall.name());
                total += safeLength(toolCall.arguments());
            }
        }
        return total;
    }

    // 判断工具结果是否超出限制，超出则压缩，没有就返回原列表；如果压缩后仍然超出则抛出异常。
    private List<LlmMessage> fitToolResults(List<LlmMessage> messages) {
        List<LlmMessage> workingMessages = new ArrayList<>(messages);
        // 没有超出限制，直接返回原列表
        if (estimateCharacters(workingMessages) <= maxContextCharacters) {
            return workingMessages;
        }

        // 从旧到新压缩工具结果；较新的结果更可能服务于当前模型决策。
        for (int index = 0; index < workingMessages.size(); index++) {
            LlmMessage message = workingMessages.get(index);
            if (!"tool".equals(message.getRole())) {
                continue;
            }
            workingMessages.set(index, compactToolMessage(message));
            // 旧结果已经释放出足够空间时立即停止，尽量保留较新的工具结果全文。
            if (estimateCharacters(workingMessages) <= maxContextCharacters) {
                return workingMessages;
            }
        }

        if (estimateCharacters(workingMessages) > maxContextCharacters) {
            // 本阶段不擅自删除用户/assistant 对话，也不调用模型做摘要，因此明确报告无法继续。
            throw new ContextWindowExceededException(
                    "上下文超过限制，压缩工具结果后仍无法容纳；摘要压缩功能尚未启用"
            );
        }
        return workingMessages;
    }

    // 压缩单个工具结果消息，如果超出限制则截断结构化结果，否则返回原消息。
    private LlmMessage compactToolMessage(LlmMessage message) {
        String content = message.getContent();
        if (content == null || content.length() <= maxToolResultCharacters) {
            return message;
        }
        // 尝试压缩结构化结果
        String compactedContent = compactStructuredResult(content);
        return LlmMessage.toolResult(message.getToolCallId(), compactedContent);
    }

    // 尝试压缩结构化工具结果，如 JSON 格式，只保留 content 字段。
    private String compactStructuredResult(String content) {
        try {
            JsonNode node = JSON_MAPPER.readTree(content);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                JsonNode resultContent = objectNode.get("content");
                if (resultContent != null && resultContent.isTextual()) {
                    objectNode.put("content", truncate(resultContent.asString(), maxToolResultCharacters));
                    return JSON_MAPPER.writeValueAsString(objectNode);
                }
            }
        } catch (JacksonException ignored) {
            // 非结构化工具结果走下面的文本兜底，不让压缩失败阻断整个请求。
        }

        // 旧工具或异常工具返回的普通文本没有 content 字段，只能安全截断原文。
        return truncate(content, maxToolResultCharacters);
    }

    // 截断文本，保留指定长度，末尾添加省略标记。
    private String truncate(String text, int maxCharacters) {
        if (text.length() <= maxCharacters) {
            return text;
        }
        // 如果设置的最大tool结果长度小于等于省略标记长度，则按照设置的长度进行截断
        if (maxCharacters <= TRUNCATED_MARKER.length()) {
            return text.substring(0, maxCharacters);
        }
        return text.substring(0, maxCharacters - TRUNCATED_MARKER.length()) + TRUNCATED_MARKER;
    }

    // 安全获取字符串长度，避免空指针异常
    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
