package com.yjjoker.learningagent.harness.memory.impl;

import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.entity.LearningSessionSummary;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.memory.service.ConversationMemoryService;
import com.yjjoker.learningagent.projectenum.LearningSessionMessageRoleEnum;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import com.yjjoker.learningagent.repository.LearningSessionSummaryRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

// 数据库会话记忆实现：负责 LlmMessage 与数据库消息实体之间的转换。
@Service
@AllArgsConstructor
@Slf4j
public class DatabaseConversationMemoryService implements ConversationMemoryService {

    // 工具调用是对象列表，需要转换成 JSON 才能写入 tool_calls 字段。
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    // Repository 只负责 SQL，本类负责消息格式和业务校验。
    private final LearningSessionMessageRepository messageRepository;
    private final LearningSessionSummaryRepository summaryRepository;


    // 按数据库中的消息顺序加载历史，并转换成 LLM 能直接使用的消息。
    @Override
    public List<LlmMessage> loadHistory(Long sessionId) {
        // 先阻止非法 ID 进入数据库查询。
        requireSessionId(sessionId);
        LearningSessionSummary latestSummary = summaryRepository.findLatestBySessionId(sessionId);
        List<LearningSessionMessage> storedMessages = latestSummary == null
                ? messageRepository.findReplayableBySessionId(sessionId)
                : messageRepository.findReplayableAfterMessageId(
                        sessionId, latestSummary.getCoveredUntilMessageId()
                );

        List<LlmMessage> history = storedMessages
                .stream()
                // 数据库实体不能直接发给模型，需要逐条还原成 LlmMessage。
                .map(this::toLlmMessage)
                .toList();
        if (latestSummary != null) {
            history = prependSummary(latestSummary, history);
        }
        log.info("加载会话上下文，sessionId={}，使用摘要={}，摘要覆盖消息ID={}，消息数={}",
                sessionId,
                latestSummary != null,
                latestSummary == null ? null : latestSummary.getCoveredUntilMessageId(),
                history.size());
        return history;
    }

    // 把 Agent Loop 中新产生的一条消息追加到指定会话。
    @Override
    @Transactional
    public void appendMessage(Long sessionId, LlmMessage message) {
        // 阻止非法 ID 进入数据库查询。
        requireSessionId(sessionId);
        if (message == null) {
            throw new LearningAgentServiceException("保存的会话消息不能为空");
        }

        // 先完成角色映射和工具调用序列化，再交给 Repository 写入。
        LearningSessionMessage storedMessage = toStoredMessage(sessionId, message);
        if (messageRepository.save(storedMessage) != 1) {
            throw new LearningAgentServiceException("保存会话消息失败，请稍后重试");
        }
    }

    // 在同一事务中保存本轮全部消息，任何一条失败都会整体回滚。
    @Override
    @Transactional
    public void appendMessages(Long sessionId, List<LlmMessage> messages) {
        requireSessionId(sessionId);
        if (messages == null) {
            throw new LearningAgentServiceException("保存的会话消息列表不能为空");
        }

        for (LlmMessage message : messages) {
            if (message == null) {
                throw new LearningAgentServiceException("保存的会话消息不能为空");
            }

            LearningSessionMessage storedMessage = toStoredMessage(sessionId, message);
            if (messageRepository.save(storedMessage) != 1) {
                throw new LearningAgentServiceException("保存会话消息失败，请稍后重试");
            }
        }
    }

    // 更新本轮工具调用的上下文副本，供后续轮次使用。
    @Override
    @Transactional
    public void updateToolContextCopies(Long sessionId, List<LlmMessage> messages) {
        requireSessionId(sessionId);
        if (messages == null) {
            throw new LearningAgentServiceException("更新的上下文消息列表不能为空");
        }

        for (LlmMessage message : messages) {
            // 只回写可重放工具消息；本轮尚未插入的新消息会在最终批量保存时带上副本。
            if (message == null
                    || !"tool".equals(message.getRole())
                    || !message.isContextReplayable()
                    || message.getContextContent() == null) {
                continue;
            }
            messageRepository.updateToolContextContent(
                    sessionId,
                    message.getToolCallId(),
                    message.getContextContent()
            );
        }
    }

    // 摘要单独保存，原始消息不改写；覆盖点决定下一次 loadHistory 从哪里继续读取。
    @Override
    @Transactional
    public void replaceReplayableHistoryWithSummary(Long sessionId, LlmMessage summaryMessage) {
        requireSessionId(sessionId);
        if (summaryMessage == null || !summaryMessage.isSummary()
                || !"assistant".equals(summaryMessage.getRole())
                || summaryMessage.getContent() == null || summaryMessage.getContent().isBlank()) {
            throw new LearningAgentServiceException("持久化的上下文摘要不能为空且必须是 assistant 消息");
        }

        Long coveredUntilMessageId = messageRepository.findMaxMessageId(sessionId);
        LearningSessionSummary summary = new LearningSessionSummary();
        summary.setSessionId(sessionId);
        summary.setSummaryContent(summaryMessage.getContent());
        summary.setCoveredUntilMessageId(coveredUntilMessageId == null ? 0L : coveredUntilMessageId);
        summary.setCreatedAt(LocalDateTime.now());
        if (summaryRepository.save(summary) != 1) {
            throw new LearningAgentServiceException("保存上下文摘要失败，请稍后重试");
        }
        log.info("上下文摘要已持久化，sessionId={}，覆盖到消息ID={}，摘要字符数={}",
                sessionId, summary.getCoveredUntilMessageId(), summaryMessage.getContent().length());
    }

    // 摘要作为第一条历史消息返回，后面的消息仍按原始数据库顺序追加。
    private List<LlmMessage> prependSummary(LearningSessionSummary summary,
                                             List<LlmMessage> messages) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(LlmMessage.summary(summary.getSummaryContent())),
                        messages.stream()
                )
                .toList();
    }

    // 将 Harness 消息转换成数据库实体。
    private LearningSessionMessage toStoredMessage(Long sessionId, LlmMessage message) {
        LearningSessionMessage storedMessage = new LearningSessionMessage();
        storedMessage.setSessionId(sessionId);
        // content 永远保存完整原文，contextContent 只保存发送给模型的压缩副本。
        storedMessage.setContent(message.getOriginalContent());
        storedMessage.setContextContent(message.getContextContent());
        storedMessage.setToolCallId(message.getToolCallId());
        // 不可重放消息仍完整落库，只在后续 loadHistory 时被 Repository 过滤。
        storedMessage.setContextReplayable(message.isContextReplayable());
        storedMessage.setCreatedAt(LocalDateTime.now());

        // LLM 使用小写角色，数据库使用枚举，写入前需要明确映射。
        switch (message.getRole()) {
            case "user" -> storedMessage.setRole(LearningSessionMessageRoleEnum.USER);
            case "assistant" -> {
                storedMessage.setRole(LearningSessionMessageRoleEnum.ASSISTANT);
                // 普通回答只保存 content；工具调用还需要单独保存调用列表。
                if (!message.getToolCalls().isEmpty()) {
                    storedMessage.setToolCallsJson(serializeToolCalls(message.getToolCalls()));
                }
            }
            case "tool" -> storedMessage.setRole(LearningSessionMessageRoleEnum.TOOL);
            // System Prompt 是应用配置，每次请求重新添加，不保存为会话数据。
            case "system" -> throw new LearningAgentServiceException("System Prompt 不能保存到会话历史");
            default -> throw new LearningAgentServiceException("无法保存未知角色的会话消息");
        }
        return storedMessage;
    }

    // 将数据库实体还原成 Harness 使用的消息。
    private LlmMessage toLlmMessage(LearningSessionMessage storedMessage) {
        if (storedMessage.getRole() == null) {
            throw new LearningAgentServiceException("数据库中的会话消息角色不能为空");
        }

        // 使用对应的工厂方法，保证每种角色需要的字段组合正确。
        return switch (storedMessage.getRole()) {
            case USER -> LlmMessage.user(storedMessage.getContent());
            case ASSISTANT -> toAssistantMessage(storedMessage);
            case TOOL -> LlmMessage.toolResultWithContextContent(
                    storedMessage.getToolCallId(),
                    storedMessage.getContent(),
                    storedMessage.getContextContent(),
                    storedMessage.isContextReplayable()
            );
        };
    }

    // ASSISTANT 可能是普通回答，也可能是请求工具，需要根据 tool_calls 区分。
    private LlmMessage toAssistantMessage(LearningSessionMessage storedMessage) {
        if (storedMessage.getToolCallsJson() == null || storedMessage.getToolCallsJson().isBlank()) {
            return LlmMessage.assistant(storedMessage.getContent());
        }
        return LlmMessage.assistantToolCalls(deserializeToolCalls(storedMessage.getToolCallsJson()));
    }

    // 保存工具调用前，将对象列表序列化为 JSON 字符串。
    private String serializeToolCalls(List<ToolCall> toolCalls) {
        try {
            return JSON_MAPPER.writeValueAsString(toolCalls);
        } catch (JacksonException exception) {
            throw new LearningAgentServiceException("工具调用信息序列化失败", exception);
        }
    }

    // 读取工具调用时，将 JSON 还原为 ToolCall 列表。
    private List<ToolCall> deserializeToolCalls(String toolCallsJson) {
        try {
            ToolCall[] toolCalls = JSON_MAPPER.readValue(toolCallsJson, ToolCall[].class);
            return Arrays.asList(toolCalls);
        } catch (JacksonException exception) {
            throw new LearningAgentServiceException("数据库中的工具调用信息无法解析", exception);
        }
    }

    // 会话 ID 必须是有效的数据库主键。
    private void requireSessionId(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw new LearningAgentServiceException("学习会话 ID 不合法");
        }
    }
}
