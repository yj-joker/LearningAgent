package com.yjjoker.learningagent.harness.approval;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.memory.service.ConversationMemoryService;
import com.yjjoker.learningagent.harness.model.AgentRunStatus;
import com.yjjoker.learningagent.repository.AgentApprovalRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.AgentRunResult;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalService;
import com.yjjoker.learningagent.notification.ApprovalNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 通用审批只管理任务、原参数、决定和检查点，不认识 MemoryCandidate 等领域对象。
@Service
@Slf4j
public class AgentApprovalService {
    private static final JsonMapper JSON = new JsonMapper();
    private final AgentApprovalRepository repository;
    private final LearningSessionRepository sessions;
    private final ConversationMemoryService history;
    private final ApprovalNotifier notifier;
    private final SessionGoalService sessionGoals;

    // 生产构造器显式标记，避免兼容测试构造器后 Spring 无法判断注入入口。
    @Autowired
    public AgentApprovalService(AgentApprovalRepository repository,
                                LearningSessionRepository sessions,
                                ConversationMemoryService history,
                                ApprovalNotifier notifier,
                                SessionGoalService sessionGoals) {
        this.repository = repository;
        this.sessions = sessions;
        this.history = history;
        this.notifier = notifier;
        this.sessionGoals = sessionGoals;
    }

    // 保留旧测试和扩展代码使用的四参数构造器；生产环境由 Spring 注入带目标服务的构造器。
    public AgentApprovalService(AgentApprovalRepository repository,
                                LearningSessionRepository sessions,
                                ConversationMemoryService history,
                                ApprovalNotifier notifier) {
        this(repository, sessions, history, notifier, null);
    }

    // 整批预检完成后一次保存；不执行工具，不调用模型，不持锁等待用户。
    @Transactional
    public AgentRunResult pause(AgentRunCheckpoint checkpoint, Map<String, String> reasons) {
        requireOwner(checkpoint.getUserId(), checkpoint.getSessionId());
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("暂停必须包含审批申请");
        }
        // 原调用 ID 必须唯一，避免批准一个调用却错误对应到另一个调用。
        var ids = new HashSet<String>();
        for (var call : checkpoint.getPendingCalls()) {
            if (call.id() == null || call.id().isBlank() || call.id().length() > 255 || !ids.add(call.id())) {
                throw new IllegalArgumentException("工具调用编号为空或重复");
            }
        }
        if (!ids.containsAll(reasons.keySet())) {
            throw new IllegalArgumentException("审批与原工具请求不匹配");
        }
        checkpoint.setBatchNumber(checkpoint.getBatchNumber() + 1);
        AgentApprovalRun run = new AgentApprovalRun();
        run.setRunId(checkpoint.getRunId());
        run.setUserId(checkpoint.getUserId());
        run.setSessionId(checkpoint.getSessionId());
        run.setBatchNumber(checkpoint.getBatchNumber());
        run.setCheckpointJson(JSON.writeValueAsString(checkpoint));
        run.setStatus(AgentRunStatus.WAITING_APPROVAL);
        requireOne(run.getBatchNumber() == 1 ? repository.insertRun(run) : repository.pauseAgain(run));
        for (var call : checkpoint.getPendingCalls()) {
            if (!reasons.containsKey(call.id())) {
                continue;
            }
            ToolApprovalRequest request = new ToolApprovalRequest();
            request.setRunId(run.getRunId());
            request.setBatchNumber(run.getBatchNumber());
            request.setToolCallId(call.id());
            request.setToolName(call.name());
            request.setArguments(call.arguments());
            request.setReason(reasons.get(call.id()));
            request.setStatus("PENDING");
            requireOne(repository.insertApproval(request));
        }
        log.info("审批检查点已保存，runId={}，batch={}，messageCount={}，approvalCount={}，未调用模型",
                run.getRunId(), run.getBatchNumber(), checkpoint.getMessages().size(), reasons.size());
        // 整批申请提交后提醒前端；不为通知再调用模型。
        notifier.changedAfterCommit(run.getUserId());
        return view(run);
    }

    // 用户只提交决定；批准这里不会执行业务工具，也不会额外请求模型。
    @Transactional
    public AgentRunResult decide(String runId, int batchNumber, String callId, boolean approved, String reason) {
        AgentApprovalRun run = requireRun(repository.lock(runId, currentUser()));
        requireOwner(run.getUserId(), run.getSessionId());
        if (run.getStatus() != AgentRunStatus.WAITING_APPROVAL || run.getBatchNumber() != batchNumber) {
            throw new ClientDataErrorException("审批批次已变化，请刷新任务");
        }
        if (reason != null && reason.length() > 500) {
            throw new ClientDataErrorException("审批理由不能超过 500 字");
        }
        String decisionReason = reason == null || reason.isBlank()
                ? (approved ? "用户批准" : "用户拒绝") : reason.strip();
        requireOne(repository.decide(runId, batchNumber, callId,
                approved ? "APPROVED" : "REJECTED", decisionReason));
        // 全批决定齐备才允许恢复，部分批准时任何工具都不提前执行。
        if (repository.pending(runId, batchNumber).isEmpty()) {
            requireOne(repository.transition(runId, run.getUserId(), "WAITING_APPROVAL", "APPROVAL_RESOLVED"));
            run.setStatus(AgentRunStatus.APPROVAL_RESOLVED);
        }
        log.info("工具审批已决定，runId={}，batch={}，toolCallId={}，approved={}，runStatus={}",
                runId, batchNumber, callId, approved, run.getStatus());
        // 同一用户的其他标签页也应看到本项决定和整批状态。
        notifier.changedAfterCommit(run.getUserId());
        return view(run);
    }

    // 恢复先抢占执行权；事务结束后 Harness 才调用工具和模型。
    @Transactional
    public AgentApprovalRun claim(String runId) {
        AgentApprovalRun run = requireRun(repository.lock(runId, currentUser()));
        requireOwner(run.getUserId(), run.getSessionId());
        if (run.getStatus() == AgentRunStatus.COMPLETED) {
            return run;
        }
        if (run.getStatus() != AgentRunStatus.APPROVAL_RESOLVED) {
            throw new ClientDataErrorException("任务尚未审完、正在执行或已失败，不能重复恢复");
        }
        requireOne(repository.transition(runId, run.getUserId(), "APPROVAL_RESOLVED", "RUNNING"));
        run.setStatus(AgentRunStatus.RUNNING);
        log.info("已取得任务恢复权，runId={}，batch={}", runId, run.getBatchNumber());
        notifier.changedAfterCommit(run.getUserId());
        return run;
    }

    // 读取检查点并核对身份和版本，拒绝损坏数据；此方法不占用数据库事务。
    public AgentRunCheckpoint restore(AgentApprovalRun run) {
        AgentRunCheckpoint checkpoint = JSON.readValue(run.getCheckpointJson(), AgentRunCheckpoint.class);
        if (checkpoint.getVersion() != 1 || !Objects.equals(checkpoint.getRunId(), run.getRunId())
                || !Objects.equals(checkpoint.getUserId(), run.getUserId())
                || !Objects.equals(checkpoint.getSessionId(), run.getSessionId())
                || checkpoint.getBatchNumber() != run.getBatchNumber()) {
            throw new IllegalStateException("审批检查点版本或身份不匹配");
        }
        // 检查点必须停在一条完整工具请求之后，不能把半截或被改写的消息交给模型。
        int start = checkpoint.getCurrentRunStartIndex();
        List<LlmMessage> messages = checkpoint.getMessages();
        if (messages == null || start < 1 || start >= messages.size()
                || !"user".equals(messages.get(start).getRole())
                || !Objects.equals(messages.get(start).getContent(), checkpoint.getUserMessage())
                || !"assistant".equals(messages.getLast().getRole())
                || !messages.getLast().getToolCalls().equals(checkpoint.getPendingCalls())
                || checkpoint.getPendingCalls().isEmpty()
                || checkpoint.getNextMemoryNumber() < 1 || checkpoint.getNextRecoveryNumber() < 1) {
            throw new IllegalStateException("审批检查点消息结构不完整");
        }
        return checkpoint;
    }

    // 获取本批决定，Harness 必须核对工具名和原参数，不能仅按工具名称放行。
    public List<ToolApprovalRequest> decisions(AgentApprovalRun run) {
        return repository.approvals(run.getRunId(), run.getBatchNumber());
    }

    // 保存完整聊天消息并清空检查点正文；同一事务一起提交，消息保存失败时不会丢掉检查点。
    @Transactional
    public void complete(String runId, Long sessionId, List<LlmMessage> messages, String answer) {
        AgentApprovalRun run = requireRun(repository.lock(runId, currentUser()));
        requireOwner(run.getUserId(), sessionId);
        if (!Objects.equals(run.getSessionId(), sessionId)) {
            throw new ClientDataErrorException("任务与会话不匹配");
        }
        requireOne(repository.complete(runId, run.getUserId(), answer));
        history.appendMessages(sessionId, messages);
        // 历史和完成状态一起提交，前端收到通知后才会读取最终回答。
        notifier.changedAfterCommit(run.getUserId());
        log.info("恢复任务已完成，已保存历史并清理检查点正文，runId={}，messageCount={}", runId, messages.size());
    }

    // 未知系统异常不自动重跑可能已经产生副作用的工具，避免重复删除或写入。
    @Transactional
    public void fail(String runId) {
        Long userId = currentUser();
        // 只有确实完成状态变更才通知，避免把重复失败请求当成新事件。
        if (repository.transition(runId, userId, "RUNNING", "FAILED") == 1) {
            notifier.changedAfterCommit(userId);
        }
        log.warn("恢复任务已停止，runId={}，不自动重放可能已经执行的工具", runId);
    }

    // 查询返回审批内容和公开状态，不返回系统提示词、完整历史或后端目标映射。
    public AgentRunResult get(String runId) {
        AgentApprovalRun run = requireRun(repository.find(runId, currentUser()));
        requireOwner(run.getUserId(), run.getSessionId());
        return view(run);
    }

    // 刷新页面或首次响应丢失时，仍能从数据库找回这个会话尚未结束的任务。
    public AgentRunResult active(Long sessionId) {
        requireOwner(currentUser(), sessionId);
        AgentApprovalRun run = repository.active(currentUser(), sessionId);
        return run == null ? null : view(run);
    }

    // 等待中的任务必须走 resume，不允许把“同意”当成普通新问题启动另一条循环。
    public void requireSessionAvailable(Long sessionId) {
        // TODO 普通新请求也需要全程会话级互斥；当前检查和暂停时唯一索引仅覆盖已有暂停任务。
        if (repository.unfinished(currentUser(), sessionId) > 0) {
            throw new ClientDataErrorException("该会话还有待审批或正在恢复的任务，请先处理原任务");
        }
    }

    // 用后端状态生成提示，等待和决定阶段都不额外调用 LLM。
    private AgentRunResult view(AgentApprovalRun run) {
        String answer = switch (run.getStatus()) {
            case COMPLETED -> run.getAnswer();
            case WAITING_APPROVAL -> "工具调用需要确认，任务已暂停；本批工具尚未执行。";
            case APPROVAL_RESOLVED -> "本批审批已处理，请继续执行原任务。";
            case RUNNING -> "原任务正在继续执行，请勿重复提交。";
            case FAILED -> "恢复任务已失败，未自动重跑工具，请检查执行日志。";
        };
        return AgentRunResult.state(run.getRunId(), run.getBatchNumber(), run.getStatus(), answer,
                repository.approvals(run.getRunId(), run.getBatchNumber()), progressOf(run));
    }

    // 等待审批时优先读取检查点；检查点清理后再读取当前数据库状态供页面展示。
    private SessionGoalProgress progressOf(AgentApprovalRun run) {
        if (run.getCheckpointJson() != null && !run.getCheckpointJson().isBlank()) {
            try {
                AgentRunCheckpoint checkpoint = JSON.readValue(run.getCheckpointJson(), AgentRunCheckpoint.class);
                SessionGoalSnapshot snapshot = checkpoint.getGoalSnapshot();
                if (snapshot != null) return SessionGoalProgress.from(snapshot);
            } catch (RuntimeException exception) {
                log.warn("审批进度检查点读取失败，runId={}，errorType={}", run.getRunId(), exception.getClass().getSimpleName());
            }
        }
        if (sessionGoals == null) return null;
        try {
            SessionGoalSnapshot current = sessionGoals.load(run.getSessionId());
            return current == null ? null : SessionGoalProgress.from(current);
        } catch (RuntimeException exception) {
            // 普通聊天没有专注目标，或者目标已不可访问时不伪造进度对象。
            log.info("当前运行没有可展示的专注进度，runId={}，reasonType={}",
                    run.getRunId(), exception.getClass().getSimpleName());
            return null;
        }
    }

    // 先验证登录用户和会话归属；批准不能替代真正的业务权限。
    private void requireOwner(Long userId, Long sessionId) {
        if (!Objects.equals(userId, currentUser())) {
            throw new ClientDataErrorException("无权访问该任务");
        }
        var session = sessions.findSessionById(sessionId)
                .orElseThrow(() -> new ClientDataErrorException("学习会话不存在"));
        if (!Objects.equals(session.getUserId(), userId) || session.getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new ClientDataErrorException("学习会话不可访问");
        }
    }

    // 拒绝不存在或其他用户的运行记录，不暴露它是否真实存在。
    private AgentApprovalRun requireRun(AgentApprovalRun run) {
        if (run == null) {
            throw new ClientDataErrorException("任务不存在或无权访问");
        }
        return run;
    }

    // 用户身份只来自认证上下文，不接受模型或 HTTP 参数传入的用户编号。
    private Long currentUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) {
            throw new ClientDataErrorException("请先登录");
        }
        return userId;
    }

    // 影响行数不正确意味着状态已经变化或写入失败，抛异常让短事务回滚。
    private void requireOne(int affected) {
        if (affected != 1) {
            throw new ClientDataErrorException("审批状态已变化或保存失败，请刷新后重试");
        }
    }
}
