package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.memory.model.MemoryCandidate;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionContext;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionTarget;
import com.yjjoker.learningagent.harness.memory.model.MemoryOperation;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.projectenum.MemoryStatusEnum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// 按本次索引中的引用执行操作；同义目标一起更新或删除，不再猜 memoryKey。
// TODO 后续为显式写入工具接入审批；本阶段不增加审批或自动过期。
// TODO 批量语义合并另行实现；本阶段保留同义记录的各自 key，只保证内容一致。
@Service
@AllArgsConstructor
@Slf4j
public class MemoryCandidatePersistenceService {
    private final StructuredMemoryService structuredMemoryService;

    // 先校验整批目标，再统一写入；任何写入失败都回滚整批操作。
    @Transactional
    public void persist(MemoryExtractionContext context, String userMessage,
                        List<MemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        // 保存入口再次校验，不能只相信上游已经检查过模型输出。
        MemoryCandidateValidator.validate(context, userMessage, candidates);
        Map<String, UserMemory> userTargets = new HashMap<>();
        Map<String, SessionMemory> sessionTargets = new HashMap<>();
        lockAndValidateTargets(context, candidates, userTargets, sessionTargets);
        log.info("记忆目标校验完成，userId={}，sessionId={}，candidateCount={}，targetCount={}",
                context.getUserId(), context.getSessionId(), candidates.size(),
                userTargets.size() + sessionTargets.size());

        for (MemoryCandidate candidate : candidates) {
            if (candidate.getOperation() == MemoryOperation.CREATE) {
                createMemory(context, candidate);
                continue;
            }
            // 同一候选可以命中多个不同 key；全部执行，避免旧偏好残留。
            for (String ref : candidate.getTargetMemoryRefs()) {
                if (candidate.getScope() == MemoryScope.USER) {
                    applyUserChange(context.getUserId(), candidate, userTargets.get(ref));
                } else {
                    applySessionChange(context.getSessionId(), candidate, sessionTargets.get(ref));
                }
            }
            log.info("记忆变更已执行，待事务提交，scope={}，operation={}，targetCount={}",
                    candidate.getScope(), candidate.getOperation(), candidate.getTargetMemoryRefs().size());
        }
        log.info("记忆候选处理完成，待事务提交，userId={}，sessionId={}，candidateCount={}",
                context.getUserId(), context.getSessionId(), candidates.size());
    }

    // 在固定顺序下锁住所有目标，先查完整批次，再开始修改数据库。
    private void lockAndValidateTargets(MemoryExtractionContext context, List<MemoryCandidate> candidates,
                                        Map<String, UserMemory> userTargets,
                                        Map<String, SessionMemory> sessionTargets) {
        Set<String> refs = candidates.stream().flatMap(candidate -> candidate.getTargetMemoryRefs().stream())
                .collect(Collectors.toSet());
        List<MemoryExtractionTarget> targets = refs.stream().map(context::resolve)
                .sorted(Comparator.comparing(MemoryExtractionTarget::getScope)
                        .thenComparing(MemoryExtractionTarget::getMemoryId)).toList();
        for (MemoryExtractionTarget target : targets) {
            // 只在数据库事务里加锁，前面的模型请求不会占用数据库锁。
            if (target.getScope() == MemoryScope.USER) {
                UserMemory memory = structuredMemoryService.lockUserMemory(context.getUserId(), target.getMemoryId());
                if (memory == null) {
                    throw new ClientDataErrorException("目标长期记忆已失效，请重新读取索引");
                }
                requireUnchanged(target, memory.getId(), memory.getUserId(), memory.getStatus(),
                        memory.getMemoryKey(), memory.getMemoryTopic(), memory.getMemorySummary(), memory.getUpdatedAt());
                userTargets.put(target.getMemoryRef(), memory);
            } else {
                SessionMemory memory = structuredMemoryService.lockSessionMemory(context.getSessionId(), target.getMemoryId());
                if (memory == null) {
                    throw new ClientDataErrorException("目标会话记忆已失效，请重新读取索引");
                }
                requireUnchanged(target, memory.getId(), memory.getSessionId(), memory.getStatus(),
                        memory.getMemoryKey(), memory.getMemoryTopic(), memory.getMemorySummary(), memory.getUpdatedAt());
                sessionTargets.put(target.getMemoryRef(), memory);
            }
        }
    }

    // 目标在模型判断期间变动时拒绝写入，避免用旧索引覆盖新记忆。
    private void requireUnchanged(MemoryExtractionTarget target, Long id, Long ownerId, MemoryStatusEnum status,
                                  String key, String topic, String summary, LocalDateTime updatedAt) {
        if (status != MemoryStatusEnum.ACTIVE || !Objects.equals(target.getMemoryId(), id)
                || !Objects.equals(target.getOwnerId(), ownerId) || !Objects.equals(target.getMemoryKey(), key)
                || !Objects.equals(target.getMemoryTopic(), topic) || !Objects.equals(target.getMemorySummary(), summary)
                || !Objects.equals(target.getUpdatedAt(), updatedAt)) {
            log.warn("记忆目标已变化，拒绝整批写入，scope={}，memoryId={}", target.getScope(), target.getMemoryId());
            throw new ClientDataErrorException("目标记忆已变化，请重新读取索引后判断");
        }
    }

    // 新增仍使用业务 key；同 key 不同内容不能偷偷覆盖已有记录。
    private void createMemory(MemoryExtractionContext context, MemoryCandidate candidate) {
        if (candidate.getScope() == MemoryScope.USER) {
            UserMemory existing = structuredMemoryService.findActiveUserMemoryByKey(context.getUserId(), candidate.getMemoryKey());
            if (existing != null) {
                requireSameContent(candidate, existing.getMemoryTopic(), existing.getMemorySummary(), existing.getMemoryContent());
                return;
            }
            UserMemory memory = new UserMemory();
            memory.setUserId(context.getUserId());
            memory.setMemoryKey(candidate.getMemoryKey());
            copyContent(candidate, memory);
            structuredMemoryService.saveUserMemory(memory);
        } else {
            SessionMemory existing = structuredMemoryService.findActiveSessionMemoryByKey(context.getSessionId(), candidate.getMemoryKey());
            if (existing != null) {
                requireSameContent(candidate, existing.getMemoryTopic(), existing.getMemorySummary(), existing.getMemoryContent());
                return;
            }
            SessionMemory memory = new SessionMemory();
            memory.setSessionId(context.getSessionId());
            memory.setMemoryKey(candidate.getMemoryKey());
            copyContent(candidate, memory);
            structuredMemoryService.saveSessionMemory(memory);
        }
        log.info("新增记忆已执行，待事务提交，scope={}，summaryCharacters={}，contentCharacters={}",
                candidate.getScope(), candidate.getMemorySummary().length(), candidate.getMemoryContent().length());
    }

    // 修改或删除已校验的长期记忆，始终保留原 ID 和 key。
    private void applyUserChange(Long userId, MemoryCandidate candidate, UserMemory memory) {
        if (candidate.getOperation() == MemoryOperation.DELETE) {
            structuredMemoryService.deleteUserMemory(userId, memory.getId());
            return;
        }
        // 更新所有选中目标的内容；不把不同 key 强行改成同一个 key。
        copyContent(candidate, memory);
        structuredMemoryService.updateUserMemory(memory);
    }

    // 修改或删除已校验的会话记忆，不越过当前会话范围。
    private void applySessionChange(Long sessionId, MemoryCandidate candidate, SessionMemory memory) {
        if (candidate.getOperation() == MemoryOperation.DELETE) {
            structuredMemoryService.deleteSessionMemory(sessionId, memory.getId());
            return;
        }
        copyContent(candidate, memory);
        structuredMemoryService.updateSessionMemory(memory);
    }

    // 复制长期记忆的新内容，不改变主键、归属和业务 key。
    private void copyContent(MemoryCandidate candidate, UserMemory memory) {
        memory.setMemoryTopic(candidate.getMemoryTopic());
        memory.setMemorySummary(candidate.getMemorySummary());
        memory.setMemoryContent(candidate.getMemoryContent());
    }

    // 复制会话记忆的新内容，不改变主键、归属和业务 key。
    private void copyContent(MemoryCandidate candidate, SessionMemory memory) {
        memory.setMemoryTopic(candidate.getMemoryTopic());
        memory.setMemorySummary(candidate.getMemorySummary());
        memory.setMemoryContent(candidate.getMemoryContent());
    }

    // 并发请求已经保存完全相同的内容时跳过；不同内容拒绝覆盖。
    private void requireSameContent(MemoryCandidate candidate, String topic, String summary, String content) {
        if (!Objects.equals(candidate.getMemoryTopic(), topic) || !Objects.equals(candidate.getMemorySummary(), summary)
                || !Objects.equals(candidate.getMemoryContent(), content)) {
            throw new ClientDataErrorException("同 key 记忆已经存在且内容不同，请重新读取索引后判断");
        }
        log.info("相同记忆已存在，跳过重复新增，scope={}", candidate.getScope());
    }
}
