package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalRequest;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalStatus;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalType;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalView;
import com.yjjoker.learningagent.harness.memory.model.MemoryCandidate;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionContext;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionTarget;
import com.yjjoker.learningagent.harness.memory.model.MemoryWriteReceipt;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.repository.MemoryApprovalRepository;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.notification.ApprovalNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 处理回答后的自动记忆提案，不再负责主循环的工具审批和暂停。
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryApprovalService {
    private static final JsonMapper JSON = new JsonMapper();
    private final MemoryApprovalRepository repository;
    private final MemoryCandidatePersistenceService persistence;
    private final LearningSessionRepository sessions;
    private final MemoryConsolidationApprovalService consolidationApprovals;
    private final MemoryConsolidationScheduler consolidationScheduler;
    private final ApprovalNotifier notifier;

    // 创建待审批申请，并保存当时的候选和目标快照。
    @Transactional
    public MemoryApprovalRequest create(Long userId, Long sessionId, MemoryCandidate candidate,
                                        MemoryExtractionContext context) {
        requireOwner(userId, sessionId);
        try {
            MemoryApprovalRequest request = buildRequest(userId, sessionId, candidate, context);
            requireInserted(repository.insert(request));
            // 自动提取的申请也主动通知用户；回滚不会发出提示。
            notifier.changedAfterCommit(userId);
            log.info("记忆审批申请已创建，userId={}，sessionId={}，approvalId={}，operation={}，scope={}",
                    userId, sessionId, request.getId(), candidate.getOperation(), candidate.getScope());
            return request;
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法保存记忆审批申请", exception);
        }
    }

    // 批准申请时重新校验会话和快照，再在同一事务中执行实际写入。
    @Transactional
    public MemoryApprovalView approve(Long approvalId) {
        Long userId = currentUser();
        MemoryApprovalRequest request = requirePending(approvalId, userId);
        requireOwner(userId, request.getSessionId());
        if (request.getApprovalType() == MemoryApprovalType.CONSOLIDATION) {
            // 整理用保存的整份方案执行，不重新请求模型；申请状态与业务写入共用外层事务。
            boolean applied = consolidationApprovals.apply(request);
            MemoryApprovalStatus status = applied ? MemoryApprovalStatus.APPROVED : MemoryApprovalStatus.STALE;
            String reason = applied ? "整理方案已批准并执行" : "原记忆或整理进度已变化，未执行旧方案";
            LocalDateTime now = LocalDateTime.now();
            if (repository.decide(request.getId(), userId, status, reason, now, now) != 1) {
                throw new ClientDataErrorException("审批状态已变化，请刷新后重试");
            }
            request.setStatus(status);
            request.setDecisionReason(reason);
            request.setDecidedAt(now);
            request.setUpdatedAt(now);
            log.info("整理审批已处理，待事务提交，approvalId={}，scope={}，status={}", request.getId(), request.getScope(), status);
            notifier.changedAfterCommit(userId);
            return MemoryApprovalView.from(request);
        }
        try {
            MemoryCandidate candidate = JSON.readValue(request.getCandidateJson(), MemoryCandidate.class);
            List<MemoryExtractionTarget> targets = readTargets(request.getTargetSnapshotJson());
            MemoryExtractionContext context = new MemoryExtractionContext(userId, request.getSessionId(), targets);
            MemoryWriteReceipt receipt = persistence.persistToolCandidate(context, candidate.getUserEvidence(), candidate);
            request.setStatus(MemoryApprovalStatus.APPROVED);
            request.setDecisionReason("用户已批准");
            LocalDateTime now = LocalDateTime.now();
            request.setDecidedAt(now);
            request.setUpdatedAt(now);
            if (repository.decide(request.getId(), userId, request.getStatus(), request.getDecisionReason(), now, now) != 1) {
                throw new ClientDataErrorException("审批状态已变化，请刷新后重试");
            }
            log.info("记忆审批已批准并写入，userId={}，sessionId={}，approvalId={}，affectedCount={}",
                    userId, request.getSessionId(), approvalId, receipt.getMemoryIds().size());
            // 用户可能在聊天结束后才批准提取提案，因此写入提交后也要检查整理条件。
            consolidationScheduler.requestAfterCommit(userId, request.getSessionId());
            notifier.changedAfterCommit(userId);
            return MemoryApprovalView.from(request);
        } catch (JacksonException exception) {
            throw new ClientDataErrorException("审批申请内容已损坏，请重新发起申请");
        }
    }

    // 拒绝申请只更新审批状态，不访问记忆表。
    @Transactional
    public MemoryApprovalView reject(Long approvalId, String reason) {
        Long userId = currentUser();
        MemoryApprovalRequest request = requirePending(approvalId, userId);
        String finalReason = reason == null || reason.isBlank() ? "用户拒绝本次记忆变更" : reason.trim();
        LocalDateTime now = LocalDateTime.now();
        if (repository.decide(request.getId(), userId, MemoryApprovalStatus.REJECTED,
                finalReason, now, now) != 1) {
            throw new ClientDataErrorException("审批状态已变化，请刷新后重试");
        }
        request.setStatus(MemoryApprovalStatus.REJECTED);
        request.setDecisionReason(finalReason);
        request.setDecidedAt(now);
        request.setUpdatedAt(now);
        log.info("记忆审批已拒绝，userId={}，sessionId={}，approvalId={}", userId, request.getSessionId(), approvalId);
        notifier.changedAfterCommit(userId);
        return MemoryApprovalView.from(request);
    }

    // 查询当前用户尚未处理的审批申请。
    public List<MemoryApprovalView> pending() {
        Long userId = currentUser();
        return repository.findPendingByUserId(userId).stream().map(MemoryApprovalView::from).toList();
    }

    // 只允许当前登录用户审批自己的申请，并且申请必须仍在等待状态。
    private MemoryApprovalRequest requirePending(Long approvalId, Long userId) {
        if (approvalId == null || approvalId <= 0) {
            throw new ClientDataErrorException("审批 ID 不合法");
        }
        MemoryApprovalRequest request = repository.findById(approvalId);
        if (request == null || !userId.equals(request.getUserId())) {
            throw new ClientDataErrorException("审批申请不存在或无权访问");
        }
        // 自动提取产生的独立提案只锁申请本身；主循环工具改走通用审批表。
        request = repository.lock(approvalId, userId);
        if (request == null) {
            throw new ClientDataErrorException("审批申请已失效");
        }
        if (request.getStatus() != MemoryApprovalStatus.PENDING) {
            throw new ClientDataErrorException("该审批申请已经处理，不能重复操作");
        }
        return request;
    }

    // 自动提取提案保存候选和目标版本；构造对象时不执行记忆操作。
    private MemoryApprovalRequest buildRequest(Long userId, Long sessionId, MemoryCandidate candidate,
                                               MemoryExtractionContext context) {
        MemoryApprovalRequest request = new MemoryApprovalRequest();
        request.setUserId(userId);
        request.setSessionId(sessionId);
        request.setOperation(candidate.getOperation());
        request.setScope(candidate.getScope());
        request.setCandidateJson(JSON.writeValueAsString(candidate));
        request.setTargetSnapshotJson(JSON.writeValueAsString(context.getTargets().stream()
                .filter(target -> candidate.getTargetMemoryRefs().contains(target.getMemoryRef())).toList()));
        request.setStatus(MemoryApprovalStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());
        request.setUpdatedAt(request.getCreatedAt());
        return request;
    }

    // 数据库未保存成功就终止事务，不能对外返回假的申请编号或等待状态。
    private void requireInserted(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("审批数据保存失败");
        }
    }

    // 批准和创建都检查用户、会话归属及会话状态。
    private void requireOwner(Long userId, Long sessionId) {
        if (userId == null || sessionId == null || !userId.equals(currentUser())) {
            throw new ClientDataErrorException("审批申请归属不一致");
        }
        var session = sessions.findSessionById(sessionId)
                .orElseThrow(() -> new ClientDataErrorException("学习会话不存在"));
        if (!userId.equals(session.getUserId()) || session.getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new ClientDataErrorException("学习会话不可审批记忆");
        }
    }

    // 从线程上下文获取登录用户，不接受请求体中的 userId。
    private Long currentUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) {
            throw new ClientDataErrorException("请先登录后处理记忆审批");
        }
        return userId;
    }

    // 将审批时保存的目标快照恢复为后端上下文，不重新生成 memoryRef。
    private List<MemoryExtractionTarget> readTargets(String json) throws JacksonException {
        JsonNode array = JSON.readTree(json);
        if (array == null || !array.isArray()) {
            throw new ClientDataErrorException("审批目标快照格式错误");
        }
        List<MemoryExtractionTarget> targets = new ArrayList<>();
        for (JsonNode node : array) {
            targets.add(new MemoryExtractionTarget(node.get("memoryRef").asString(),
                    com.yjjoker.learningagent.harness.memory.model.MemoryScope.valueOf(node.get("scope").asString()),
                    node.get("ownerId").asLong(), node.get("memoryId").asLong(),
                    node.get("memoryKey").asString(), node.get("memoryTopic").asString(),
                    node.get("memorySummary").asString(),
                    node.get("updatedAt").isNull() ? null : LocalDateTime.parse(node.get("updatedAt").asString())));
        }
        return List.copyOf(targets);
    }
}
