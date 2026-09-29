package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.repository.MemoryApprovalRepository;
import com.yjjoker.learningagent.notification.ApprovalNotifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 整理只产生独立提案；主模型的工具审批和 AgentLoop 检查点不参与这条流程。
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryConsolidationApprovalService {
    private static final JsonMapper JSON = new JsonMapper();
    private final MemoryApprovalRepository repository;
    private final LearningSessionRepository sessions;
    private final MemoryConsolidationPersistenceService persistence;
    private final ApprovalNotifier notifier;

    // 先检查数据库中的申请，进程重启后仍记得待审批和已拒绝的版本。
    public boolean hasProposal(MemoryConsolidationState state) {
        return repository.countBlockingConsolidations(state.getScope(), state.getOwnerId(),
                state.getChangeCount(), state.getProcessedCount()) > 0;
    }

    // 后台任务显式传入身份，并重新核对归属；不从请求线程的 ThreadLocal 取用户。
    @Transactional
    public void submit(Long userId, Long sessionId, MemoryConsolidationSnapshot snapshot, MemoryConsolidationPlan plan) {
        var session = sessions.findSessionById(sessionId).orElseThrow(() -> new IllegalStateException("整理会话不存在"));
        if (!Objects.equals(session.getUserId(), userId) || session.getStatus() != LearningSessionStatusEnum.ACTIVE
                || !Objects.equals(snapshot.getOwnerId(), snapshot.getScope() == MemoryScope.USER ? userId : sessionId)) {
            throw new IllegalStateException("整理申请归属不一致或会话已关闭");
        }
        MemoryConsolidationValidator.validate(snapshot, plan);
        if (plan.getMerges().isEmpty()) {
            throw new IllegalArgumentException("没有实际修改的方案不创建审批");
        }
        // 保存完整方案和原数据版本；用户批准时不能再次问模型换一份方案。
        MemoryApprovalRequest request = new MemoryApprovalRequest();
        request.setUserId(userId);
        request.setSessionId(sessionId);
        request.setApprovalType(MemoryApprovalType.CONSOLIDATION);
        request.setScope(snapshot.getScope());
        request.setCandidateJson(JSON.writeValueAsString(plan));
        request.setTargetSnapshotJson(JSON.writeValueAsString(snapshot));
        request.setSnapshotChangeCount(snapshot.getChangeCount());
        request.setSnapshotProcessedCount(snapshot.getProcessedCount());
        request.setStatus(MemoryApprovalStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());
        request.setUpdatedAt(request.getCreatedAt());
        if (repository.insert(request) != 1) {
            throw new IllegalStateException("整理审批保存失败");
        }
        log.info("整理提案已保存，待事务提交，approvalId={}，scope={}，ownerId={}，mergeGroups={}，changeCount={}，未修改记忆",
                request.getId(), snapshot.getScope(), snapshot.getOwnerId(), plan.getMerges().size(), snapshot.getChangeCount());
        // 后台线程显式传递 userId；无需恢复请求线程里的登录上下文。
        notifier.changedAfterCommit(userId);
    }

    // 上层审批事务持有申请锁；实际写入复用原有事务，失败时申请和记忆一起回滚。
    public boolean apply(MemoryApprovalRequest request) {
        MemoryConsolidationSnapshot snapshot = readSnapshot(request.getTargetSnapshotJson());
        Long ownerId = request.getScope() == MemoryScope.USER ? request.getUserId() : request.getSessionId();
        if (request.getApprovalType() != MemoryApprovalType.CONSOLIDATION || snapshot.getScope() != request.getScope()
                || !Objects.equals(snapshot.getOwnerId(), ownerId)
                || !Objects.equals(request.getSnapshotChangeCount(), snapshot.getChangeCount())
                || !Objects.equals(request.getSnapshotProcessedCount(), snapshot.getProcessedCount())) {
            throw new IllegalArgumentException("整理审批快照与申请不匹配");
        }
        MemoryConsolidationPlan plan = JSON.readValue(request.getCandidateJson(), MemoryConsolidationPlan.class);
        // 返回 false 表示旧数据已经变化，交给审批服务保存 STALE，不误报批准成功。
        return persistence.persist(snapshot, plan);
    }

    // 显式恢复不可变快照，复用原构造器的归属、编号和重复目标校验。
    private MemoryConsolidationSnapshot readSnapshot(String content) {
        JsonNode root = JSON.readTree(content);
        if (root == null || !root.isObject() || !root.path("entries").isArray()
                || !root.path("ownerId").isIntegralNumber() || !root.path("changeCount").isIntegralNumber()
                || !root.path("processedCount").isIntegralNumber()) {
            throw new IllegalArgumentException("整理快照格式不完整");
        }
        MemoryConsolidationState state = new MemoryConsolidationState();
        state.setScope(MemoryScope.valueOf(root.path("scope").asString()));
        state.setOwnerId(root.path("ownerId").asLong());
        state.setChangeCount(root.path("changeCount").asLong());
        state.setProcessedCount(root.path("processedCount").asLong());
        var entries = new ArrayList<MemoryConsolidationEntry>();
        for (JsonNode node : root.get("entries")) {
            // 缺字段属于损坏数据，不能悄悄把缺失正文解析成字符串后继续执行。
            if (!node.isObject() || !node.path("memoryId").isIntegralNumber()
                    || !node.path("memoryRef").isTextual() || !node.path("memoryKey").isTextual()
                    || !node.path("memoryTopic").isTextual() || !node.path("memorySummary").isTextual()
                    || !node.path("memoryContent").isTextual()) {
                throw new IllegalArgumentException("整理快照中的目标字段不完整");
            }
            JsonNode version = node.get("updatedAt");
            entries.add(new MemoryConsolidationEntry(node.path("memoryRef").asString(), node.path("memoryId").asLong(),
                    node.path("memoryKey").asString(), node.path("memoryTopic").asString(), node.path("memorySummary").asString(),
                    node.path("memoryContent").asString(), version == null || version.isNull() ? null : LocalDateTime.parse(version.asString())));
        }
        return new MemoryConsolidationSnapshot(state, entries);
    }
}
