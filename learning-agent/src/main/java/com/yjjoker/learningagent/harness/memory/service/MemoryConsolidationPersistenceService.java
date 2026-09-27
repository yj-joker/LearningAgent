package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationEntry;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationPlan;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationSnapshot;
import com.yjjoker.learningagent.harness.memory.model.MemoryMergeCandidate;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.projectenum.MemoryStatusEnum;
import com.yjjoker.learningagent.repository.MemoryConsolidationRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 合并正文、软删除来源和推进整理进度在同一个短事务里完成。
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryConsolidationPersistenceService {
    private final StructuredMemoryService memoryService;
    private final MemoryConsolidationRepository progressRepository;

    // 重新校验完整快照，避免模型思考期间产生的新内容被旧方案覆盖。
    @Transactional
    public boolean persist(MemoryConsolidationSnapshot snapshot, MemoryConsolidationPlan plan) {
        MemoryConsolidationValidator.validate(snapshot, plan);
        Map<String, UserMemory> users = new HashMap<>();
        Map<String, SessionMemory> sessions = new HashMap<>();
        // 先按 ID 锁记忆，再锁进度；正常提取保存也使用这个顺序。
        for (MemoryConsolidationEntry entry : snapshot.getEntries().stream()
                .sorted(Comparator.comparing(MemoryConsolidationEntry::getMemoryId)).toList()) {
            if (snapshot.getScope() == MemoryScope.USER) {
                UserMemory memory = memoryService.lockUserMemory(snapshot.getOwnerId(), entry.getMemoryId());
                if (memory == null) {
                    throw new IllegalStateException("整理目标已失效");
                }
                requireUnchanged(snapshot, entry, memory.getId(), memory.getUserId(), memory.getStatus(),
                        memory.getMemoryKey(), memory.getMemoryTopic(), memory.getMemorySummary(),
                        memory.getMemoryContent(), memory.getUpdatedAt());
                users.put(entry.getMemoryRef(), memory);
            } else {
                SessionMemory memory = memoryService.lockSessionMemory(snapshot.getOwnerId(), entry.getMemoryId());
                if (memory == null) {
                    throw new IllegalStateException("整理目标已失效");
                }
                requireUnchanged(snapshot, entry, memory.getId(), memory.getSessionId(), memory.getStatus(),
                        memory.getMemoryKey(), memory.getMemoryTopic(), memory.getMemorySummary(),
                        memory.getMemoryContent(), memory.getUpdatedAt());
                sessions.put(entry.getMemoryRef(), memory);
            }
        }
        var progress = progressRepository.lock(snapshot.getScope(), snapshot.getOwnerId());
        // 已被另一请求整理，或期间又有正常记忆变更时，放弃整个旧方案。
        if (progress == null || progress.getChangeCount() != snapshot.getChangeCount()
                || progress.getProcessedCount() != snapshot.getProcessedCount()
                || snapshot.getChangeCount() <= snapshot.getProcessedCount()) {
            log.info("记忆整理进度已变化，跳过旧方案，scope={}，ownerId={}", snapshot.getScope(), snapshot.getOwnerId());
            return false;
        }
        for (MemoryMergeCandidate merge : plan.getMerges()) {
            if (snapshot.getScope() == MemoryScope.USER) {
                mergeUsers(snapshot.getOwnerId(), merge, users);
            } else {
                mergeSessions(snapshot.getOwnerId(), merge, sessions);
            }
        }
        // 冲突只报告引用，不改原文；未来可以在这里接入用户确认。
        for (var refs : plan.getConflicts()) {
            log.warn("记忆冲突未解决，保留原记录，scope={}，ownerId={}，memoryIds={}", snapshot.getScope(),
                    snapshot.getOwnerId(), refs.stream().map(ref -> snapshot.resolve(ref).getMemoryId()).toList());
        }
        if (progressRepository.markProcessed(snapshot.getScope(), snapshot.getOwnerId(),
                snapshot.getChangeCount(), snapshot.getProcessedCount()) != 1) {
            throw new IllegalStateException("保存整理进度失败，回滚本次合并");
        }
        log.info("记忆整理写入完成，待事务提交，scope={}，ownerId={}，mergeGroups={}，conflictGroups={}，processedCount={}",
                snapshot.getScope(), snapshot.getOwnerId(), plan.getMerges().size(),
                plan.getConflicts().size(), snapshot.getChangeCount());
        return true;
    }

    // 连同正文一起比较，避免索引相同但原文已经改变时继续合并。
    private void requireUnchanged(MemoryConsolidationSnapshot snapshot, MemoryConsolidationEntry entry,
                                  Long id, Long ownerId, MemoryStatusEnum status, String key, String topic,
                                  String summary, String content, LocalDateTime updatedAt) {
        if (status != MemoryStatusEnum.ACTIVE || !Objects.equals(snapshot.getOwnerId(), ownerId)
                || !Objects.equals(entry.getMemoryId(), id) || !Objects.equals(entry.getMemoryKey(), key)
                || !Objects.equals(entry.getMemoryTopic(), topic) || !Objects.equals(entry.getMemorySummary(), summary)
                || !Objects.equals(entry.getMemoryContent(), content) || !Objects.equals(entry.getUpdatedAt(), updatedAt)) {
            throw new IllegalStateException("整理目标已变化，保留原记录，等待下次重新判断");
        }
    }

    // 更新保留项，再软删除其他长期记忆；保留原 ID、key 和创建时间。
    private void mergeUsers(Long userId, MemoryMergeCandidate merge, Map<String, UserMemory> targets) {
        UserMemory keep = targets.get(merge.getKeepRef());
        keep.setMemoryTopic(merge.getMemoryTopic());
        keep.setMemorySummary(merge.getMemorySummary());
        keep.setMemoryContent(merge.getMemoryContent());
        memoryService.updateUserMemory(keep);
        for (String ref : merge.getSourceRefs()) {
            if (!ref.equals(merge.getKeepRef())) {
                memoryService.deleteUserMemory(userId, targets.get(ref).getId());
            }
        }
        log.info("长期记忆合并已执行，userId={}，keepId={}，removedCount={}，contentCharacters={}",
                userId, keep.getId(), merge.getSourceRefs().size() - 1, keep.getMemoryContent().length());
    }

    // 更新保留项，再软删除其他会话记忆；不会把会话内容提升为长期记忆。
    private void mergeSessions(Long sessionId, MemoryMergeCandidate merge, Map<String, SessionMemory> targets) {
        SessionMemory keep = targets.get(merge.getKeepRef());
        keep.setMemoryTopic(merge.getMemoryTopic());
        keep.setMemorySummary(merge.getMemorySummary());
        keep.setMemoryContent(merge.getMemoryContent());
        memoryService.updateSessionMemory(keep);
        for (String ref : merge.getSourceRefs()) {
            if (!ref.equals(merge.getKeepRef())) {
                memoryService.deleteSessionMemory(sessionId, targets.get(ref).getId());
            }
        }
        log.info("会话记忆合并已执行，sessionId={}，keepId={}，removedCount={}，contentCharacters={}",
                sessionId, keep.getId(), merge.getSourceRefs().size() - 1, keep.getMemoryContent().length());
    }
}
