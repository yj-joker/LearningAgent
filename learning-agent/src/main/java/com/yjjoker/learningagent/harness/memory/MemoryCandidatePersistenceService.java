package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 把提取出的候选记忆按作用域写入对应表。
// 当前阶段只执行新增，不查询同名记录，也不执行更新或去重。
@Service
@AllArgsConstructor
@Slf4j
public class MemoryCandidatePersistenceService {

    private final StructuredMemoryService structuredMemoryService;

    @Transactional
    public void persist(Long userId,
                        Long sessionId,
                        List<MemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            // 没有候选时不访问数据库，避免产生无意义的写操作。
            return;
        }
        // 验证 ID 是否合理
        requirePositiveId(userId, "用户 ID");
        requirePositiveId(sessionId, "会话 ID");

        // 遍历并保存每个候选记忆
        for (MemoryCandidate candidate : candidates) {
            persistOne(userId, sessionId, candidate);
        }
        log.info("记忆候选持久化完成，userId={}，sessionId={}，candidateCount={}",
                userId, sessionId, candidates.size());
    }

    // 根据候选作用域组装不同实体，归属 ID 由服务端补充，不能由模型提供。
    private void persistOne(Long userId,
                            Long sessionId,
                            MemoryCandidate candidate) {
        if (candidate == null || candidate.getScope() == null) {
            throw new ClientDataErrorException("记忆候选作用域不能为空");
        }

        // 处理用户长期记忆
        if (candidate.getScope() == MemoryScope.USER) {
            UserMemory memory = new UserMemory();
            memory.setUserId(userId);
            copyFields(candidate, memory);
            structuredMemoryService.saveUserMemory(memory);
            log.info("新增用户长期记忆成功，userId={}，memoryKey={}，summaryCharacters={}，contentCharacters={}",
                    userId,
                    memory.getMemoryKey(),
                    safeLength(memory.getMemorySummary()),
                    safeLength(memory.getMemoryContent()));
            return;
        }

        // 处理会话记忆
        if (candidate.getScope() == MemoryScope.SESSION) {
            SessionMemory memory = new SessionMemory();
            memory.setSessionId(sessionId);
            copyFields(candidate, memory);
            structuredMemoryService.saveSessionMemory(memory);
            log.info("新增会话记忆成功，sessionId={}，memoryKey={}，summaryCharacters={}，contentCharacters={}",
                    sessionId,
                    memory.getMemoryKey(),
                    safeLength(memory.getMemorySummary()),
                    safeLength(memory.getMemoryContent()));
            return;
        }

        throw new ClientDataErrorException("不支持的记忆作用域");
    }

    // 两类实体字段相同，分别复制可持久化字段，避免把模型对象直接交给数据库层。
    private void copyFields(MemoryCandidate candidate, UserMemory memory) {
        memory.setMemoryKey(candidate.getMemoryKey());
        memory.setMemoryTopic(candidate.getMemoryTopic());
        memory.setMemorySummary(candidate.getMemorySummary());
        memory.setMemoryContent(candidate.getMemoryContent());
    }

    private void copyFields(MemoryCandidate candidate, SessionMemory memory) {
        memory.setMemoryKey(candidate.getMemoryKey());
        memory.setMemoryTopic(candidate.getMemoryTopic());
        memory.setMemorySummary(candidate.getMemorySummary());
        memory.setMemoryContent(candidate.getMemoryContent());
    }

    private void requirePositiveId(Long id, String fieldName) {
        if (id == null || id <= 0) {
            throw new ClientDataErrorException(fieldName + "必须大于 0");
        }
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
