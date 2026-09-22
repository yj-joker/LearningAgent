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

// ContextManager 只管理发送给模型的上下文副本，不删除工具原文或数据库历史。
// 当前阶段统一压缩普通工具和恢复工具的结果，摘要和长期记忆留到后续阶段。
@Component
public class ContextManager {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();
    private static final String TRUNCATED_MARKER = "\n[工具结果已截断，原始结果未删除]";

    private final int maxContextCharacters;
    private final int maxToolResultCharacters;
    private final int maxRecoveryCallsPerRun;
    private final int maxRecoveryCharactersPerRun;
    private final double safeContextRatio;
    private final double compressionTargetRatio;

    // 存在测试专用构造方法，因此显式告诉 Spring 生产环境应使用配置对象创建组件。
    @Autowired
    public ContextManager(HarnessContextProperties properties) {
        this(
                properties.getMaxContextCharacters(),
                properties.getMaxToolResultCharacters(),
                properties.getMaxRecoveryCallsPerRun(),
                properties.getMaxRecoveryCharactersPerRun(),
                properties.getSafeContextRatio(),
                properties.getCompressionTargetRatio()
        );
    }

    // 测试可以直接传入较小阈值，验证压缩行为而不需要修改 Spring 配置。
    public ContextManager(int maxContextCharacters, int maxToolResultCharacters) {
        this(maxContextCharacters, maxToolResultCharacters, 2, 600, 0.95);
    }

    // 完整构造方法供 Harness 预算测试使用，生产参数仍由 application.yml 统一注入。
    public ContextManager(int maxContextCharacters,
                          int maxToolResultCharacters,
                          int maxRecoveryCallsPerRun,
                          int maxRecoveryCharactersPerRun,
                          double safeContextRatio) {
        this(
                maxContextCharacters,
                maxToolResultCharacters,
                maxRecoveryCallsPerRun,
                maxRecoveryCharactersPerRun,
                safeContextRatio,
                0.80
        );
    }

    // 生产配置可以同时指定安全上限和压缩目标，测试可用此构造方法验证两者关系。
    public ContextManager(int maxContextCharacters,
                          int maxToolResultCharacters,
                          int maxRecoveryCallsPerRun,
                          int maxRecoveryCharactersPerRun,
                          double safeContextRatio,
                          double compressionTargetRatio) {
        if (maxContextCharacters <= 0
                || maxToolResultCharacters <= 0
                || maxRecoveryCallsPerRun <= 0
                || maxRecoveryCharactersPerRun <= 0
                || safeContextRatio <= 0
                || safeContextRatio >= 1
                || compressionTargetRatio <= 0
                || compressionTargetRatio >= safeContextRatio) {
            throw new IllegalArgumentException("上下文压缩阈值必须满足 0 < 目标阈值 < 安全阈值 < 1");
        }
        this.maxContextCharacters = maxContextCharacters;
        this.maxToolResultCharacters = maxToolResultCharacters;
        this.maxRecoveryCallsPerRun = maxRecoveryCallsPerRun;
        this.maxRecoveryCharactersPerRun = maxRecoveryCharactersPerRun;
        this.safeContextRatio = safeContextRatio;
        this.compressionTargetRatio = compressionTargetRatio;
    }

    // 每次请求模型前都使用安全水位检查，避免普通工具结果占满模型窗口。
    public List<LlmMessage> prepareForLlmRequest(List<LlmMessage> messages) {
        return fitToolResults(messages);
    }

    // 普通工具和恢复工具执行后都走这里，与请求前保持同一套压缩规则。
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

    // 恢复次数达到上限后拒绝继续执行，防止模型反复读取原始结果。
    public boolean hasRecoveryCallCapacity(int completedRecoveryCalls) {
        return completedRecoveryCalls < maxRecoveryCallsPerRun;
    }

    // 累计字符只统计成功恢复并实际准备交给模型的内容。
    public boolean hasRecoveryCharacterCapacity(int recoveredCharacters, int nextRecoveredCharacters) {
        return recoveredCharacters + nextRecoveredCharacters <= maxRecoveryCharactersPerRun;
    }

    // 超过安全水位时从旧到新压缩工具结果；压缩后仍放不下才终止请求。
    private List<LlmMessage> fitToolResults(List<LlmMessage> messages) {
        List<LlmMessage> workingMessages = new ArrayList<>(messages);
        // 计算安全水位，即最大字符数乘以安全比例
        int safeContextCharacters = (int) Math.floor(maxContextCharacters * safeContextRatio);
        // 只有超过安全水位才触发压缩；触发后尽量压到更低的目标水位。
        int compressionTargetCharacters = (int) Math.floor(maxContextCharacters * compressionTargetRatio);
        // 如果当前消息总和未超过安全水位，则无需压缩
        if (estimateCharacters(workingMessages) <= safeContextCharacters) {
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
            if (estimateCharacters(workingMessages) <= compressionTargetCharacters) {
                return workingMessages;
            }
        }

        if (estimateCharacters(workingMessages) > safeContextCharacters) {
            // 本阶段不擅自删除用户/assistant 对话，也不调用模型做摘要，因此明确报告无法继续。
            // TODO 后续接入摘要压缩：优先保留用户目标、最近工具结果和关键 assistant 结论，再重新估算。
            throw new ContextWindowExceededException(
                    "上下文超过安全水位，压缩工具结果后仍无法容纳；摘要压缩功能尚未启用"
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
        // 新消息只替换上下文副本，完整原文和是否允许重放的标记保持不变。
        return message.withContextContent(compactedContent);
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
