package com.yjjoker.learningagent.harness.context;

import com.yjjoker.learningagent.config.HarnessContextProperties;
import com.yjjoker.learningagent.exception.ContextWindowExceededException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// ContextManager 只管理发送给模型的上下文副本，不删除工具原文或数据库历史。
// 工具结果压缩后仍超限时，可交给摘要器继续压缩旧历史。
@Component
@Slf4j
public class ContextManager {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();
    private static final String TRUNCATED_MARKER_PREFIX = "\n[工具结果已截断，原始结果未删除；恢复引用=";

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
        return fitToolResults(messages, new RecoveryReferenceRegistry());
    }

    // Agent Loop 使用此重载，让请求前和工具执行后的压缩共享同一张引用表。
    public List<LlmMessage> prepareForLlmRequest(List<LlmMessage> messages,
                                                 RecoveryReferenceRegistry referenceRegistry) {
        return fitToolResults(messages, referenceRegistry);
    }

    // 工具结果压缩仍不够时，只摘要当前轮之前的历史，保留系统提示词和当前任务消息。
    public List<LlmMessage> prepareForLlmRequest(List<LlmMessage> messages,
                                                 RecoveryReferenceRegistry referenceRegistry,
                                                 ContextSummarizer summarizer,
                                                 int currentRunStartIndex) {
        // 首先尝试压缩工具结果。
        List<LlmMessage> compactedMessages = compactToolResults(messages, referenceRegistry);
        // 计算安全水位
        int safeContextCharacters = safeContextCharacters();
        // 没有超过安全水位，直接返回
        if (estimateCharacters(compactedMessages) <= safeContextCharacters) {
            return compactedMessages;
        }

        log.info("上下文工具结果压缩后仍超限，准备摘要旧历史，压缩后字符数={}，安全上限={}，历史消息数={}",
                estimateCharacters(compactedMessages), safeContextCharacters,
                Math.max(0, Math.min(currentRunStartIndex, compactedMessages.size()) - 1));

        // currentRunStartIndex 指向本轮用户消息，不能把当前任务一起摘要掉。
        if (summarizer == null || currentRunStartIndex <= 1) {
            throw contextWindowExceeded();
        }

        // 获取当前任务之前的已经压缩的消息，使用min防止越界
        int historyEndIndex = Math.min(currentRunStartIndex, compactedMessages.size());
        List<LlmMessage> historyMessages = new ArrayList<>(
                compactedMessages.subList(1, historyEndIndex)
        );
        if (historyMessages.isEmpty()) {
            throw contextWindowExceeded();
        }

        // 使用摘要器对历史消息进行摘要
        String summary = summarizer.summarize(historyMessages);
        List<LlmMessage> summarizedMessages = new ArrayList<>();
        // 保留系统提示词和当前任务消息
        summarizedMessages.add(compactedMessages.getFirst());
        // 添加摘要消息
        summarizedMessages.add(LlmMessage.summary(summary));
        // 添加本轮AgentLoop产生的剩余消息
        summarizedMessages.addAll(
                compactedMessages.subList(historyEndIndex, compactedMessages.size())
        );

        // 摘要本身也必须重新估算，不能假设摘要一定比原文短。
        if (estimateCharacters(summarizedMessages) > safeContextCharacters) {
            log.warn("上下文摘要后仍超过安全上限，摘要后字符数={}，安全上限={}",
                    estimateCharacters(summarizedMessages), safeContextCharacters);
            throw contextWindowExceeded();
        }
        // 没有超过，发生摘要，从本轮AgentLoop当中去除已经摘要的工具映射
        pruneRecoveryReferences(summarizedMessages, referenceRegistry);
        log.info("上下文摘要完成，摘要前字符数={}，摘要后字符数={}，摘要消息数={}",
                estimateCharacters(compactedMessages),
                estimateCharacters(summarizedMessages),
                historyMessages.size());
        return summarizedMessages;
    }

    // 普通工具和恢复工具执行后都走这里，与请求前保持同一套压缩规则。
    public List<LlmMessage> compactAfterToolExecution(List<LlmMessage> messages) {
        return fitToolResults(messages, new RecoveryReferenceRegistry());
    }

    // 工具结果刚产生后继续使用同一张表，模型下一轮才能使用刚看到的 result_1。
    public List<LlmMessage> compactAfterToolExecution(List<LlmMessage> messages,
                                                       RecoveryReferenceRegistry referenceRegistry) {
        return fitToolResults(messages, referenceRegistry);
    }

    // 工具执行后也允许触发同一套摘要流程，保证两次上下文检查的行为一致。
    public List<LlmMessage> compactAfterToolExecution(
            List<LlmMessage> messages,
            RecoveryReferenceRegistry referenceRegistry,
            ContextSummarizer summarizer,
            int currentRunStartIndex) {
        return prepareForLlmRequest(
                messages,
                referenceRegistry,
                summarizer,
                currentRunStartIndex
        );
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
    private List<LlmMessage> fitToolResults(List<LlmMessage> messages,
                                            RecoveryReferenceRegistry referenceRegistry) {
        List<LlmMessage> workingMessages = compactToolResults(messages, referenceRegistry);
        if (estimateCharacters(workingMessages) > safeContextCharacters()) {
            throw contextWindowExceededWithoutSummary();
        }
        return workingMessages;
    }

    // 只完成工具结果压缩并返回工作副本，让摘要阶段还能拿到压缩后的消息继续处理。
    private List<LlmMessage> compactToolResults(List<LlmMessage> messages,
                                                RecoveryReferenceRegistry referenceRegistry) {
        List<LlmMessage> workingMessages = new ArrayList<>(messages);
        refreshExistingRecoveryReferences(workingMessages, referenceRegistry);
        // 计算安全水位，即最大字符数乘以安全比例
        int safeContextCharacters = safeContextCharacters();
        // 只有超过安全水位才触发压缩；触发后尽量压到更低的目标水位。
        int compressionTargetCharacters = (int) Math.floor(maxContextCharacters * compressionTargetRatio);
        // 如果当前消息总和未超过安全水位，则无需压缩
        if (estimateCharacters(workingMessages) <= safeContextCharacters) {
            //
            pruneRecoveryReferences(workingMessages, referenceRegistry);
            return workingMessages;
        }

        // 从旧到新压缩工具结果；较新的结果更可能服务于当前模型决策。
        for (int index = 0; index < workingMessages.size(); index++) {
            LlmMessage message = workingMessages.get(index);
            if (!"tool".equals(message.getRole())) {
                continue;
            }
            workingMessages.set(index, compactToolMessage(message, referenceRegistry));
            if (workingMessages.get(index).getContextContent() != null) {
                log.info("工具结果上下文副本已压缩，toolCallId={}，原文字符数={}，副本字符数={}",
                        message.getToolCallId(),
                        safeLength(message.getOriginalContent()),
                        safeLength(workingMessages.get(index).getContextContent()));
            }
            // 旧结果已经释放出足够空间时立即停止，尽量保留较新的工具结果全文。
            if (estimateCharacters(workingMessages) <= compressionTargetCharacters) {
                //发生摘要，从本轮AgentLoop当中去除已经摘要的工具映射
                pruneRecoveryReferences(workingMessages, referenceRegistry);
                return workingMessages;
            }
        }
        // 发生摘要，从本轮AgentLoop当中去除已经摘要的工具映射
        pruneRecoveryReferences(workingMessages, referenceRegistry);
        return workingMessages;
    }

    // 当前上下文中没有出现的工具结果，不能继续让模型通过旧引用恢复。
    private void pruneRecoveryReferences(List<LlmMessage> messages,
                                         RecoveryReferenceRegistry referenceRegistry) {
        Set<String> visibleToolCallIds = new HashSet<>();
        for (LlmMessage message : messages) {
            if ("tool".equals(message.getRole())
                    && message.getContextContent() != null
                    && message.getToolCallId() != null) {
                visibleToolCallIds.add(message.getToolCallId());
            }
        }
        //
        int removedCount = referenceRegistry.retainToolCallIds(visibleToolCallIds);
        if (removedCount > 0) {
            log.info("已清理过期恢复引用，清理数量={}，当前可用引用={}",
                    removedCount, referenceRegistry.references());
        }
    }

    // 计算安全水位，即最大字符数乘以安全比例
    private int safeContextCharacters() {
        return (int) Math.floor(maxContextCharacters * safeContextRatio);
    }

    private ContextWindowExceededException contextWindowExceeded() {
        return new ContextWindowExceededException(
                "上下文超过安全水位，工具结果压缩和摘要后仍无法容纳"
        );
    }

    private ContextWindowExceededException contextWindowExceededWithoutSummary() {
        return new ContextWindowExceededException(
                "上下文超过安全水位，压缩工具结果后仍无法容纳；摘要压缩功能尚未启用"
        );
    }

    // 历史中已经被截断的工具结果也要重新分配本轮引用，不能沿用上一次请求的 result_1。
    private void refreshExistingRecoveryReferences(List<LlmMessage> messages,
                                                   RecoveryReferenceRegistry referenceRegistry) {
        for (int index = 0; index < messages.size(); index++) {
            LlmMessage message = messages.get(index);
            if (!"tool".equals(message.getRole())
                    || message.getContextContent() == null
                    || message.getToolCallId() == null) {
                continue;
            }
            String reference = referenceRegistry.register(message.getToolCallId());
            messages.set(index, message.withContextContent(
                    addRecoveryReference(message.getContextContent(), reference)
            ));
        }
    }

    // 压缩单个工具结果消息，如果超出限制则截断结构化结果，否则返回原消息。
    private LlmMessage compactToolMessage(LlmMessage message,
                                          RecoveryReferenceRegistry referenceRegistry) {
        String reference = referenceRegistry.register(message.getToolCallId());
        if (message.getContextContent() != null) {
            return message.withContextContent(
                    addRecoveryReference(message.getContextContent(), reference)
            );
        }
        String content = message.getContent();
        if (content == null || content.length() <= maxToolResultCharacters) {
            return message;
        }
        // 尝试压缩结构化结果
        String compactedContent = compactStructuredResult(content, reference);
        // 新消息只替换上下文副本，完整原文和是否允许重放的标记保持不变。
        return message.withContextContent(compactedContent);
    }

    // 尝试压缩结构化工具结果，如 JSON 格式，只保留 content 字段。
    private String compactStructuredResult(String content, String reference) {
        try {
            JsonNode node = JSON_MAPPER.readTree(content);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                JsonNode resultContent = objectNode.get("content");
                if (resultContent != null && resultContent.isTextual()) {
                    objectNode.put("content", truncate(resultContent.asString(), maxToolResultCharacters, reference));
                    objectNode.put("recoveryRef", reference);
                    return JSON_MAPPER.writeValueAsString(objectNode);
                }
            }
        } catch (JacksonException ignored) {
            // 非结构化工具结果走下面的文本兜底，不让压缩失败阻断整个请求。
        }

        // 旧工具或异常工具返回的普通文本没有 content 字段，只能安全截断原文。
        return truncate(content, maxToolResultCharacters, reference);
    }

    // 截断文本，保留指定长度，末尾添加省略标记。
    private String truncate(String text, int maxCharacters, String reference) {
        if (text.length() <= maxCharacters) {
            return text;
        }
        String marker = TRUNCATED_MARKER_PREFIX + reference + "]";
        // 如果设置的最大tool结果长度小于等于省略标记长度，则按照设置的长度进行截断
        if (maxCharacters <= marker.length()) {
            return text.substring(0, maxCharacters);
        }
        return text.substring(0, maxCharacters - marker.length()) + marker;
    }

    // 为已经截断的历史副本替换当前请求的短引用；结构化结果写入 recoveryRef 字段。
    private String addRecoveryReference(String content, String reference) {
        try {
            JsonNode node = JSON_MAPPER.readTree(content);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                objectNode.put("recoveryRef", reference);
                return JSON_MAPPER.writeValueAsString(objectNode);
            }
        } catch (JacksonException ignored) {
            // 普通文本结果使用下面的标记方式，不影响恢复流程。
        }

        String markerStart = "\n[工具结果已截断";
        int start = content.indexOf(markerStart);
        if (start >= 0) {
            int end = content.indexOf(']', start);
            if (end >= 0) {
                return content.substring(0, start)
                        + TRUNCATED_MARKER_PREFIX + reference + "]"
                        + content.substring(end + 1);
            }
        }
        return content + TRUNCATED_MARKER_PREFIX + reference + "]";
    }

    // 安全获取字符串长度，避免空指针异常
    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
