package com.yjjoker.learningagent.harness.impl;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.harness.AgentHarnessService;
import com.yjjoker.learningagent.harness.hook.AgentHook;
import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.hook.ToolCallHookResult;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.context.InMemoryOriginalToolResultStore;
import com.yjjoker.learningagent.harness.context.OriginalToolResultStore;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.memory.ConversationMemoryService;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

// Harness 的业务实现类，负责安排模型调用流程，而不是负责拼接厂商 HTTP 请求。
// 完整流程是“请求模型 -> 判断结果类型 -> 必要时执行工具 -> 回传工具结果 -> 再请求模型”。
@Slf4j
@Service
public class AgentHarnessServiceImpl implements AgentHarnessService {

    // LLM 接口中的 tool content 是字符串，因此需要把 ToolExecutionResult 转成 JSON 文本。
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    // 最多允许模型连续进行五轮工具调用，防止模型反复调用工具形成死循环并持续消耗费用。
    // 这里限制的是工具轮数；模型在第五轮工具执行后仍有一次机会生成最终文本。
    private static final int MAX_TOOL_ROUNDS = 5;

    // 字段类型使用 LlmClient 接口，而不是 AliyunLlmClient 具体类。
    // 这样 Harness 不会和阿里云绑定，测试时也能传入不访问网络的假客户端。
    private final LlmClient llmClient;

    // Harness 通过注册表按照模型返回的工具名称找到真正的 Java 工具对象。
    private final ToolRegistry toolRegistry;

    // Spring 会收集所有 AgentHook 实现类并注入列表，Harness 不需要依赖某个具体 Hook。
    private final List<AgentHook> hooks;

    // 会话记忆服务负责读取历史，并在本轮成功后批量保存新增消息。
    private final ConversationMemoryService conversationMemoryService;

    // 会话仓库用于确认当前用户只能访问自己的进行中会话。
    private final LearningSessionRepository learningSessionRepository;

    // 上下文管理器只修改发送给模型的工作副本，不影响本轮完整消息的持久化内容。
    private final ContextManager contextManager;

    // 保存本轮工具的完整结果，供 get_original_tool_result 在同一轮中按片段读取。
    private final OriginalToolResultStore originalToolResultStore;

    // Spring 注入配置化的 ContextManager；其他依赖仍通过接口接入，便于测试替换。
    @org.springframework.beans.factory.annotation.Autowired
    // List.copyOf 防止外部在 Harness 运行期间修改 Hook 列表。
    public AgentHarnessServiceImpl(LlmClient llmClient,
                                   ToolRegistry toolRegistry,
                                   List<AgentHook> hooks,
                                   ConversationMemoryService conversationMemoryService,
                                   LearningSessionRepository learningSessionRepository,
                                   ContextManager contextManager,
                                   OriginalToolResultStore originalToolResultStore) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.hooks = List.copyOf(hooks);
        this.conversationMemoryService = conversationMemoryService;
        this.learningSessionRepository = learningSessionRepository;
        this.contextManager = contextManager;
        this.originalToolResultStore = originalToolResultStore;
    }

    // 保留测试和旧调用方的五参数构造方法；生产环境使用上面的 Spring 构造方法读取配置。
    public AgentHarnessServiceImpl(LlmClient llmClient,
                                   ToolRegistry toolRegistry,
                                   List<AgentHook> hooks,
                                   ConversationMemoryService conversationMemoryService,
                                   LearningSessionRepository learningSessionRepository) {
        this(llmClient, toolRegistry, hooks, conversationMemoryService,
                learningSessionRepository, new ContextManager(40_000, 8_000),
                new InMemoryOriginalToolResultStore());
    }

    // 测试可以替换上下文限制，但不需要额外准备原文存储实现。
    public AgentHarnessServiceImpl(LlmClient llmClient,
                                   ToolRegistry toolRegistry,
                                   List<AgentHook> hooks,
                                   ConversationMemoryService conversationMemoryService,
                                   LearningSessionRepository learningSessionRepository,
                                   ContextManager contextManager) {
        this(llmClient, toolRegistry, hooks, conversationMemoryService,
                learningSessionRepository, contextManager, new InMemoryOriginalToolResultStore());
    }

    @Override
    public String run(Long sessionId, String userMessage) {
        // 每次 HTTP 请求都会得到独立上下文，用于保存本次任务的工具轨迹和完成状态。
        AgentRunContext context = new AgentRunContext();

        try {
            // 验证用户输入
            validateUserMessage(userMessage);
            // 验证会话访问权限
            validateSessionAccess(sessionId);
            // 给原始工具结果恢复工具设置当前会话范围，后续数据库查询不会跨会话读取。
            originalToolResultStore.beginSession(sessionId);
            // 运行 Agent 循环，得到最终结果
            String answer = runAgentLoop(sessionId, userMessage, context);
            // 标记任务成功
            context.markSucceeded();
            return answer;
        } catch (RuntimeException exception) {
            // 标记任务失败
            context.markFailed(exception);
            throw exception;
        } finally {
            // finally 保证正常回答和异常终止都会触发任务结束的所有 Hook。
            notifyAfterRun(context);
            // 原文只在当前 Agent Loop 内提供恢复能力，任务结束后立即释放内存缓存。
            originalToolResultStore.clear();
        }
    }

    // Controller 之外的代码也可能调用 Harness，因此服务层仍需校验用户输入。
    private void validateUserMessage(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new ClientDataErrorException("用户消息不能为空");
        }
        if (userMessage.length() > 10_000) {
            throw new ClientDataErrorException("用户消息不能超过 10000 个字符");
        }
    }

    private String runAgentLoop(Long sessionId, String userMessage, AgentRunContext context) {
        // messages 是发送给模型的完整上下文，包含系统提示词、旧历史和本轮消息。
        List<LlmMessage> messages = new ArrayList<>();
        // newMessages 只收集本轮内容，避免把数据库中的旧历史重复保存。
        List<LlmMessage> newMessages = new ArrayList<>();

        messages.add(LlmMessage.system(AgentSystemPrompt.CONTENT));
        messages.addAll(conversationMemoryService.loadHistory(sessionId));

        LlmMessage currentUserMessage = LlmMessage.user(userMessage);
        messages.add(currentUserMessage);
        newMessages.add(currentUserMessage);

        // 记录工具轮数，防止模型无限嵌套工具调用
        int completedToolRounds = 0;
        // 恢复预算只在当前 Agent Loop 内统计，下一次用户请求会重新计算。
        int completedRecoveryCalls = 0;
        int recoveredCharacters = 0;
        while (true) {
            // generate 只生成“模型下一步”，它可能是最终文本，也可能是需要 Java 执行的工具调用。
            // 得到模型的回复
            // 请求前先检查上下文；超限时只压缩工具结果，用户和 assistant 消息保持不变。
            messages = contextManager.prepareForLlmRequest(messages);
            LlmResponse response = llmClient.generate(messages);

            //是最终结果？
            if (response instanceof TextLlmResponse textResponse) {
                LlmMessage assistantMessage = LlmMessage.assistant(textResponse.content());
                newMessages.add(assistantMessage);
                // 只有完整得到最终回答后，才在一个事务中保存本轮全部消息。
                conversationMemoryService.appendMessages(sessionId, newMessages);
                return textResponse.content();
            }

            // 是工具调用？
            if (response instanceof ToolCallLlmResponse toolCallResponse) {
                if (completedToolRounds >= MAX_TOOL_ROUNDS) {
                    throw new IllegalStateException("Harness 超过最多 5 轮工具调用，已停止继续执行");
                }

                // 获取LLM想要调用的工具列表
                List<ToolCall> toolCalls = toolCallResponse.toolCalls();

                // 只要包含恢复工具，整组 assistant/tool 消息就不进入未来上下文，避免调用与结果数量不一致。
                // TODO 下一阶段要求恢复工具独占一轮：Prompt 负责提示，Harness 负责强制校验。
                // TODO 如果模型混用工具，则不执行本轮任何工具，并为每个 toolCall 返回可重试失败，让模型重新规划。
                boolean recoveryRound = containsRecoveryTool(toolCalls);
                boolean contextReplayable = !recoveryRound;

                //将本轮LLM的工具请求添加到上下文，标记是否可重放
                LlmMessage assistantToolMessage = LlmMessage.assistantToolCalls(toolCalls, contextReplayable);
                messages.add(assistantToolMessage);

                // 本轮工具调用先暂存；全部处理完成后才加入待保存消息。
                List<LlmMessage> currentToolRoundMessages = new ArrayList<>();
                currentToolRoundMessages.add(assistantToolMessage);

                // 一次响应可能要求调用多个工具，所以必须逐个执行并分别添加结果消息。
                for (ToolCall toolCall : toolCalls) {
                    // 在已经注册的工具中找到对应名称的工具
                    Tool tool = toolRegistry.getRequiredTool(toolCall.name());
                    boolean recoveryTool = tool.isContextRecoveryTool();

                    // 次数限制在工具执行前检查；被拒绝的请求不执行恢复工具和 afterToolExecution Hook。
                    if (recoveryTool && !contextManager.hasRecoveryCallCapacity(completedRecoveryCalls)) {
                        LlmMessage rejectedRecoveryMessage = createToolResultMessage(
                                toolCall.id(),
                                recoveryFailure(
                                        "RECOVERY_CALL_LIMIT_EXCEEDED",
                                        "本次任务的工具结果恢复次数已达到上限"
                                ),
                                false
                        );
                        messages.add(rejectedRecoveryMessage);
                        currentToolRoundMessages.add(rejectedRecoveryMessage);
                        continue;
                    }

                    // 只要恢复工具开始执行就计数，即使参数错误或找不到原始结果也会消耗一次机会。
                    if (recoveryTool) {
                        completedRecoveryCalls++;
                    }

                    // 顺序执行前置 Hook；第一个拒绝结果会立即停止后续前置 Hook。
                    ToolCallHookResult hookResult = notifyBeforeToolExecution(context, toolCall);

                    // 如果 Hook 不允许LLM调用该工具并且不可重试，则立即返回结果。
                    if (!hookResult.isAllowed()) {
                        if (!hookResult.isRetryable()) {
                            // 当前工具轮没有完成，不保存其 assistant/tool 消息，只保存Hook直接返回的答复。
                            newMessages.add(LlmMessage.assistant(hookResult.getMessage()));
                            conversationMemoryService.appendMessages(sessionId, newMessages);
                            return hookResult.getMessage();
                        }

                        // 可恢复问题不执行工具，也不执行 afterToolExecution。
                        // 使用原 toolCallId 返回失败结果，让模型知道应该修正哪一次工具调用。
                        ToolExecutionResult rejectedResult = ToolExecutionResult.failure(
                                hookResult.getErrorCode(),
                                hookResult.getMessage(),
                                true
                        );
                        LlmMessage rejectedToolMessage = LlmMessage.toolResult(
                                toolCall.id(),
                                serializeToolResult(rejectedResult),
                                contextReplayable
                        );
                        messages.add(rejectedToolMessage);
                        currentToolRoundMessages.add(rejectedToolMessage);
                        continue;
                    }

                    //全部before Hook执行完才记录为已执行；如果未来安全 Hook 拒绝，这里不会运行。
                    context.recordToolExecution(toolCall.name());

                    // 执行工具并获取结果，异常会在这里终止请求。
                    ToolExecutionResult toolResult = executeTool(tool, toolCall.arguments());

                    if (recoveryTool && toolResult.isSuccess()) {
                        //计算本次恢复工具恢复的文本数
                        int nextRecoveredCharacters = safeLength(toolResult.getContent());

                        // 累计字符超限时丢弃本次恢复正文，只向模型返回不可重试的结构化失败。
                        if (!contextManager.hasRecoveryCharacterCapacity(
                                recoveredCharacters,
                                nextRecoveredCharacters
                        )) {
                            toolResult = recoveryFailure(
                                    "RECOVERY_CHARACTER_LIMIT_EXCEEDED",
                                    "本次任务可恢复的工具结果字符数已达到上限"
                            );
                        } else {
                            LlmMessage candidateMessage = createToolResultMessage(
                                    toolCall.id(),
                                    toolResult,
                                    false
                            );

                            // 恢复结果加入后的完整 messages 必须低于最大上下文的 95%。
                            if (!contextManager.fitsRecoverySafetyLimit(messages, candidateMessage)) {
                                // TODO 下一阶段先强制压缩旧工具结果，再重新估算；仍超限时才返回恢复失败。
                                toolResult = recoveryFailure(
                                        "CONTEXT_RECOVERY_BUDGET_EXCEEDED",
                                        "当前上下文剩余空间不足，无法恢复更多工具内容"
                                );
                            } else {
                                recoveredCharacters += nextRecoveredCharacters;
                            }
                        }
                    }

                    // 执行全部 afterToolExecution Hook。
                    notifyAfterToolExecution(context, toolCall, toolResult);

                    // 将工具结果转换为 JSON 字符串
                    String toolResultJson = serializeToolResult(toolResult);

                    // 恢复工具自己的结果不进入原文存储，防止模型继续恢复“恢复结果”。
                    if (!recoveryTool) {
                        originalToolResultStore.save(toolCall.id(), toolResultJson);
                    }

                    // 使用原始调用 id 建立一一对应关系，不能使用工具名称代替 id。
                    // 同一个工具在一轮中可能被调用两次，而这两次调用会拥有不同的 id。
                    LlmMessage toolResultMessage = LlmMessage.toolResult(
                            toolCall.id(),
                            toolResultJson,
                            contextReplayable
                    );
                    messages.add(toolResultMessage);
                    currentToolRoundMessages.add(toolResultMessage);
                }

                // 工具结果可能来自 RAG 或联网搜索，加入后立即再次检查上下文是否超过限制，避免下一轮请求才发现过大。
                // newMessages 不参与替换，确保数据库仍保存完整原始工具结果。
                messages = contextManager.compactAfterToolExecution(messages);

                // 保存所有本次执行工具信息。当前工具轮的每个调用都有结果后，才允许作为完整历史保存。
                newMessages.addAll(currentToolRoundMessages);
                completedToolRounds++;
                // 工具执行后不能直接把结果返回用户，因为工具只提供原始数据。
                // 回到循环顶部，把结果交给模型，让模型结合用户问题组织最终自然语言答案。
                continue;
            }

            // LlmResponse 是 sealed 类型，正常情况下只有上面两种实现；这个检查用于防御空返回值。
            throw new IllegalStateException("LLM 客户端返回了无法识别的结果");
        }
    }

    // 会话必须存在、属于当前登录用户，并且仍处于进行中状态。
    private void validateSessionAccess(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw new LearningSessionStatusException("学习会话 ID 不合法");
        }
        if (BaseContext.isCurrentIdNull()) {
            throw new LearningSessionStatusException("当前用户未登录");
        }

        LearningSession session = learningSessionRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFountException("学习会话不存在"));

        if (!session.getUserId().equals(BaseContext.getCurrentId())) {
            throw new LearningSessionStatusException("无权访问该学习会话");
        }
        if (session.getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new LearningSessionStatusException("学习会话已结束");
        }
    }

    // 顺序执行前置 Hook；一旦某个 Hook 拒绝，后面的 Hook 不再执行。
    private ToolCallHookResult notifyBeforeToolExecution(AgentRunContext context, ToolCall toolCall) {
        for (AgentHook hook : hooks) {
            ToolCallHookResult result = hook.beforeToolExecution(context, toolCall);
            if (result == null) {
                throw new IllegalStateException("beforeToolExecution Hook 返回结果不能为空");
            }
            // 如果某个 Hook 拒绝了工具调用，立即返回结果，不再执行后续 Hook。
            if (!result.isAllowed()) {
                return result;
            }
        }
        return ToolCallHookResult.allow();
    }

    // 工具正常返回后执行所有工具后置 Hook。
    private void notifyAfterToolExecution(AgentRunContext context,
                                          ToolCall toolCall,
                                          ToolExecutionResult result) {
        for (AgentHook hook : hooks) {
            try {
                hook.afterToolExecution(context, toolCall, result);
            } catch (RuntimeException exception) {
                // 当前后置 Hook 只用于观察，Hook 自身故障不能破坏已经完成的工具调用。
                log.error("Agent 工具后置 Hook 执行失败，hookType={}, runId={}, toolName={}",
                        hook.getClass().getSimpleName(), context.getRunId(), toolCall.name(), exception);
            }
        }
    }

    // 执行所有后置Hook
    private void notifyAfterRun(AgentRunContext context) {
        for (AgentHook hook : hooks) {
            try {
                hook.afterRun(context);
            } catch (RuntimeException exception) {
                // 结束日志属于辅助功能，不能因为某个 Hook 故障而覆盖任务原本的回答或异常。
                log.error("Agent 结束 Hook 执行失败，hookType={}, runId={}",
                        hook.getClass().getSimpleName(), context.getRunId(), exception);
            }
        }
    }

    // 执行工具并返回结果
    private ToolExecutionResult executeTool(Tool tool, String arguments) {
        try {
            //执行工具
            ToolExecutionResult result = tool.execute(arguments);
            if (result == null) {
                throw new IllegalStateException("工具返回结果不能为空");
            }
            return result;
        } catch (RuntimeException exception) {
            // 完整异常只写入服务端日志，避免把数据库或代码内部信息暴露给 LLM。
            log.error("工具执行发生系统异常，toolName={}", tool.name(), exception);
            throw new LearningAgentServiceException("工具执行失败，请稍后重试", exception);
        }
    }

    // 判断当前 assistant 工具请求中是否包含恢复工具。
    private boolean containsRecoveryTool(List<ToolCall> toolCalls) {
        for (ToolCall toolCall : toolCalls) {
            if (toolRegistry.getRequiredTool(toolCall.name()).isContextRecoveryTool()) {
                return true;
            }
        }
        return false;
    }

    // 恢复预算失败不是系统异常，模型收到稳定错误码后应停止继续恢复。
    private ToolExecutionResult recoveryFailure(String errorCode, String message) {
        return ToolExecutionResult.failure(errorCode, message, false);
    }

    // 统一完成恢复预算检查所需的“结果对象 -> JSON -> tool 消息”转换。
    private LlmMessage createToolResultMessage(String toolCallId,
                                               ToolExecutionResult result,
                                               boolean contextReplayable) {
        return LlmMessage.toolResult(
                toolCallId,
                serializeToolResult(result),
                contextReplayable
        );
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    // 序列化工具结果为 JSON 字符串
    private String serializeToolResult(ToolExecutionResult result) {
        try {
            // 结构化 JSON 让模型能明确读取 success、errorCode、message 和 retryable。
            return JSON_MAPPER.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new LearningAgentServiceException("工具结果序列化失败", exception);
        }
    }
}
