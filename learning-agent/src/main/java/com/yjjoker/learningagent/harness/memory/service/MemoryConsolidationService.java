package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.config.MemoryConsolidationProperties;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationEntry;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationPlan;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationSnapshot;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationState;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.projectenum.MemoryStatusEnum;
import com.yjjoker.learningagent.repository.MemoryConsolidationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 回答完成后按累计变更触发整理；模型调用期间不持有数据库事务。
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryConsolidationService {
    private final MemoryConsolidationProperties properties;
    private final MemoryConsolidationRepository progressRepository;
    private final StructuredMemoryService memoryService;
    private final MemoryConsolidator consolidator;
    private final MemoryConsolidationPersistenceService persistenceService;
    private final MemoryConsolidationApprovalService approvals;

    // 用户和会话分别检查阈值，一类整理失败不阻止另一类，也不影响最终回答。
    public void consolidateIfNeeded(Long userId, Long sessionId) {
        if (!properties.isEnabled()) {
            return;
        }
        for (MemoryScope scope : MemoryScope.values()) {
            Long ownerId = scope == MemoryScope.USER ? userId : sessionId;
            try {
                consolidateScope(userId, sessionId, scope);
            } catch (RuntimeException exception) {
                // 失败不清计数，下次仍可尝试；不输出异常中可能携带的业务正文。
                log.warn("记忆整理未完成，保留待整理进度，scope={}，ownerId={}，errorType={}",
                        scope, ownerId, exception.getClass().getSimpleName());
            }
        }
    }

    // 判断一个范围是否达到阈值，再读取完整快照并请求模型。
    public void consolidateScope(Long userId, Long sessionId, MemoryScope scope) {
        // 调度器按范围调用此入口，也必须遵守停用开关。
        if (!properties.isEnabled()) {
            return;
        }
        if (userId == null || userId <= 0 || sessionId == null || sessionId <= 0 || scope == null) {
            throw new IllegalArgumentException("整理任务缺少有效归属");
        }
        Long ownerId = scope == MemoryScope.USER ? userId : sessionId;
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("记忆整理归属 ID 必须大于零");
        }
        MemoryConsolidationState state = progressRepository.find(scope, ownerId);
        List<Long> ids = null;
        if (state == null) {
            // 首次接入把已有有效记忆计入工作量，让历史重复记录也能触发整理。
            ids = loadIds(scope, ownerId);
            progressRepository.initialize(scope, ownerId, ids.size());
            state = progressRepository.find(scope, ownerId);
        }
        if (state == null) {
            throw new IllegalStateException("记忆整理进度尚未建立");
        }
        long pending = state.getChangeCount() - state.getProcessedCount();
        if (pending < properties.getChangeThreshold()) {
            log.debug("记忆整理未达阈值，scope={}，ownerId={}，pending={}，threshold={}",
                    scope, ownerId, pending, properties.getChangeThreshold());
            return;
        }
        // 相同版本被拒绝或已有申请时不再生成；不能用内存状态替代数据库中的审批决定。
        if (approvals.hasProposal(state)) {
            log.info("跳过重复整理提案，scope={}，ownerId={}，changeCount={}", scope, ownerId, state.getChangeCount());
            return;
        }
        if (ids == null) {
            ids = loadIds(scope, ownerId);
        }
        // 超大范围暂时跳过；后续分批整理需要另行维护扫描边界，不能随意截取后删除。
        if (ids.size() > properties.getMaxMemories()) {
            log.warn("记忆数量超过单次整理上限，保留进度，scope={}，ownerId={}，count={}，limit={}",
                    scope, ownerId, ids.size(), properties.getMaxMemories());
            return;
        }
        MemoryConsolidationSnapshot snapshot = loadSnapshot(state, ids);
        // 零条或一条无需模型判断，但仍确认这批变更已经检查完。
        MemoryConsolidationPlan plan = ids.size() < 2 ? new MemoryConsolidationPlan() : consolidator.consolidate(snapshot);
        MemoryConsolidationValidator.validate(snapshot, plan);
        if (!plan.getMerges().isEmpty()) {
            // 有实际修改时只保存申请；此处绝不修改记忆或推进 processedCount。
            approvals.submit(userId, sessionId, snapshot, plan);
        } else if (persistenceService.persist(snapshot, plan)) {
            // 空方案或只有冲突提示时不修改记忆，无需用户批准空操作。
            log.info("整理检查完成，无记忆变更，scope={}，ownerId={}，checkedCount={}，conflictGroups={}",
                    scope, ownerId, ids.size(), plan.getConflicts().size());
        }
    }

    // 先读取索引中的 ID，按固定顺序为本次整理分配引用。
    private List<Long> loadIds(MemoryScope scope, Long ownerId) {
        if (scope == MemoryScope.USER) {
            return memoryService.loadUserMemoryIndex(ownerId).stream().map(memory -> {
                requireOwner(ownerId, memory.getUserId(), memory.getStatus());
                return memory.getId();
            }).sorted().toList();
        }
        return memoryService.loadSessionMemoryIndex(ownerId).stream().map(memory -> {
            requireOwner(ownerId, memory.getSessionId(), memory.getStatus());
            return memory.getId();
        }).sorted().toList();
    }

    // 读取正文而不加锁，避免模型请求长期占用数据库锁。
    private MemoryConsolidationSnapshot loadSnapshot(MemoryConsolidationState state, List<Long> ids) {
        List<MemoryConsolidationEntry> entries = new ArrayList<>();
        long contentCharacters = 0;
        for (Long id : ids) {
            String ref = "memory_" + (entries.size() + 1);
            MemoryConsolidationEntry entry;
            if (state.getScope() == MemoryScope.USER) {
                var memory = memoryService.recallUserMemory(state.getOwnerId(), id);
                requireOwner(state.getOwnerId(), memory.getUserId(), memory.getStatus());
                entry = new MemoryConsolidationEntry(ref, memory.getId(), memory.getMemoryKey(),
                        memory.getMemoryTopic(), memory.getMemorySummary(), memory.getMemoryContent(), memory.getUpdatedAt());
            } else {
                var memory = memoryService.recallSessionMemory(state.getOwnerId(), id);
                requireOwner(state.getOwnerId(), memory.getSessionId(), memory.getStatus());
                entry = new MemoryConsolidationEntry(ref, memory.getId(), memory.getMemoryKey(),
                        memory.getMemoryTopic(), memory.getMemorySummary(), memory.getMemoryContent(), memory.getUpdatedAt());
            }
            if (!Objects.equals(id, entry.getMemoryId()) || entry.getMemoryContent() == null || entry.getMemoryContent().isBlank()) {
                throw new IllegalStateException("记忆整理目标或正文不完整");
            }
            contentCharacters += entry.getMemoryContent().length();
            if (contentCharacters > properties.getMaxInputCharacters()) {
                log.warn("记忆正文超过整理上限，scope={}，ownerId={}，characters={}，limit={}",
                        state.getScope(), state.getOwnerId(), contentCharacters, properties.getMaxInputCharacters());
                throw new IllegalStateException("记忆正文超过整理上限");
            }
            entries.add(entry);
        }
        log.info("记忆整理快照已加载，scope={}，ownerId={}，memoryCount={}，contentCharacters={}",
                state.getScope(), state.getOwnerId(), entries.size(), contentCharacters);
        return new MemoryConsolidationSnapshot(state, entries);
    }

    // 后端检查范围与状态，不让其他用户的记录进入整理快照。
    private void requireOwner(Long expected, Long actual, MemoryStatusEnum status) {
        if (!Objects.equals(expected, actual) || status != MemoryStatusEnum.ACTIVE) {
            throw new IllegalStateException("记忆整理范围或状态不匹配");
        }
    }
}
