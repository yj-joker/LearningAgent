package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.NotFountException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

// 按作用域执行候选记忆的新增、更新和软删除。
// 当前只处理准确 memoryKey，不处理不同 key 之间的语义合并。
// TODO 后续为 UPDATE/DELETE 接入用户审批，当前阶段先完成生命周期基础能力。
// TODO 后续讨论“过期”语义；不能默认按时间自动清理用户记忆。
@Service
@AllArgsConstructor
@Slf4j
public class MemoryCandidatePersistenceService {

    private final StructuredMemoryService structuredMemoryService;

    // 逐条处理本轮提取出的记忆候选。
    @Transactional
    public void persist(Long userId,
                        Long sessionId,
                        List<MemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            // 没有候选时不访问数据库，避免产生无意义的写操作。
            return;
        }
        // 先检查归属 ID，防止把记忆写入错误的用户或会话。
        requirePositiveId(userId, "用户 ID");
        requirePositiveId(sessionId, "会话 ID");

        // 每个候选独立判断作用域和操作类型。
        for (MemoryCandidate candidate : candidates) {
            persistOne(userId, sessionId, candidate);
        }
        log.info("记忆候选生命周期处理完成，userId={}，sessionId={}，candidateCount={}",
                userId, sessionId, candidates.size());
    }

    // 根据作用域分流，归属 ID 由服务端补充，不能由模型提供。
    private void persistOne(Long userId,
                            Long sessionId,
                            MemoryCandidate candidate) {
        // 先检查模型是否提供了执行操作所需的字段。
        if (candidate == null || candidate.getScope() == null) {
            throw new ClientDataErrorException("记忆候选作用域不能为空");
        }
        if (candidate.getOperation() == null) {
            throw new ClientDataErrorException("记忆候选操作不能为空");
        }

        if (candidate.getScope() == MemoryScope.USER) {
            persistUserCandidate(userId, candidate);
            return;
        }
        if (candidate.getScope() == MemoryScope.SESSION) {
            persistSessionCandidate(sessionId, candidate);
            return;
        }
        throw new ClientDataErrorException("不支持的记忆作用域");
    }

    // 用户长期记忆的查找始终带 userId，避免同一个 key 串到其他用户。
    private void persistUserCandidate(Long userId, MemoryCandidate candidate) {
        // 用户记忆只能在当前用户范围内查找。
        UserMemory existing = structuredMemoryService.findActiveUserMemoryByKey(
                userId, candidate.getMemoryKey()
        );
        switch (candidate.getOperation()) {
            case CREATE -> createUserMemory(userId, candidate, existing);
            case UPDATE -> updateUserMemory(userId, candidate, existing);
            case DELETE -> deleteUserMemory(userId, candidate, existing);
        }
    }

    // 会话记忆的定位范围是 sessionId，防止误更新其他会话的记忆。
    private void persistSessionCandidate(Long sessionId, MemoryCandidate candidate) {
        // 会话记忆只能在当前会话范围内查找。
        SessionMemory existing = structuredMemoryService.findActiveSessionMemoryByKey(
                sessionId, candidate.getMemoryKey()
        );
        switch (candidate.getOperation()) {
            case CREATE -> createSessionMemory(sessionId, candidate, existing);
            case UPDATE -> updateSessionMemory(sessionId, candidate, existing);
            case DELETE -> deleteSessionMemory(sessionId, candidate, existing);
        }
    }

    private void createUserMemory(Long userId,
                                  MemoryCandidate candidate,
                                  UserMemory existing) {
        // 相同 key 已有相同内容时直接跳过，避免重复写入。
        if (existing != null) {
            if (sameContent(candidate, existing.getMemoryTopic(),
                    existing.getMemorySummary(), existing.getMemoryContent())) {
                log.info("用户长期记忆已存在且内容相同，跳过新增，userId={}，memoryKey={}",
                        userId, candidate.getMemoryKey());
                return;
            }
            throw new ClientDataErrorException("用户长期记忆已存在，请使用 UPDATE 操作");
        }

        // 没有同 key 记忆时创建新实体。
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        copyFields(candidate, memory);
        structuredMemoryService.saveUserMemory(memory);
        logSave("用户长期记忆", userId, candidate);
    }

    private void updateUserMemory(Long userId,
                                  MemoryCandidate candidate,
                                  UserMemory existing) {
        // UPDATE 必须找到原记忆，否则不能猜测要更新哪一条。
        if (existing == null) {
            throw new NotFountException("需要更新的用户长期记忆不存在");
        }
        // 保留原主键，只替换记忆内容。
        existing.setUserId(userId);
        copyFields(candidate, existing);
        structuredMemoryService.updateUserMemory(existing);
        log.info("更新用户长期记忆成功，userId={}，memoryId={}，memoryKey={}",
                userId, existing.getId(), existing.getMemoryKey());
    }

    private void deleteUserMemory(Long userId,
                                  MemoryCandidate candidate,
                                  UserMemory existing) {
        // DELETE 只改变状态，不直接删除数据库记录。
        if (existing == null) {
            // 删除是幂等操作，目标已经不存在时不重复抛错。
            log.info("用户长期记忆已不存在，跳过删除，userId={}，memoryKey={}",
                    userId, candidate.getMemoryKey());
            return;
        }
        structuredMemoryService.deleteUserMemory(userId, existing.getId());
        log.info("删除用户长期记忆成功，userId={}，memoryId={}，memoryKey={}",
                userId, existing.getId(), existing.getMemoryKey());
    }

    private void createSessionMemory(Long sessionId,
                                     MemoryCandidate candidate,
                                     SessionMemory existing) {
        // 相同 key 已有相同内容时直接跳过，避免重复写入。
        if (existing != null) {
            if (sameContent(candidate, existing.getMemoryTopic(),
                    existing.getMemorySummary(), existing.getMemoryContent())) {
                log.info("会话记忆已存在且内容相同，跳过新增，sessionId={}，memoryKey={}",
                        sessionId, candidate.getMemoryKey());
                return;
            }
            throw new ClientDataErrorException("会话记忆已存在，请使用 UPDATE 操作");
        }

        // 没有同 key 记忆时创建新实体。
        SessionMemory memory = new SessionMemory();
        memory.setSessionId(sessionId);
        copyFields(candidate, memory);
        structuredMemoryService.saveSessionMemory(memory);
        logSave("会话记忆", sessionId, candidate);
    }

    private void updateSessionMemory(Long sessionId,
                                     MemoryCandidate candidate,
                                     SessionMemory existing) {
        // UPDATE 必须找到原记忆，避免更新错误目标。
        if (existing == null) {
            throw new NotFountException("需要更新的会话记忆不存在");
        }
        // 保留原主键，只替换记忆内容。
        existing.setSessionId(sessionId);
        copyFields(candidate, existing);
        structuredMemoryService.updateSessionMemory(existing);
        log.info("更新会话记忆成功，sessionId={}，memoryId={}，memoryKey={}",
                sessionId, existing.getId(), existing.getMemoryKey());
    }

    private void deleteSessionMemory(Long sessionId,
                                     MemoryCandidate candidate,
                                     SessionMemory existing) {
        // DELETE 只改变状态，保留原记录方便审计。
        if (existing == null) {
            log.info("会话记忆已不存在，跳过删除，sessionId={}，memoryKey={}",
                    sessionId, candidate.getMemoryKey());
            return;
        }
        structuredMemoryService.deleteSessionMemory(sessionId, existing.getId());
        log.info("删除会话记忆成功，sessionId={}，memoryId={}，memoryKey={}",
                sessionId, existing.getId(), existing.getMemoryKey());
    }

    // 两类实体字段相同，分别复制可持久化字段，避免把模型对象直接交给数据库层。
    private void copyFields(MemoryCandidate candidate, UserMemory memory) {
        // 把模型候选中的内容复制到数据库实体。
        memory.setMemoryKey(candidate.getMemoryKey());
        memory.setMemoryTopic(candidate.getMemoryTopic());
        memory.setMemorySummary(candidate.getMemorySummary());
        memory.setMemoryContent(candidate.getMemoryContent());
    }

    private void copyFields(MemoryCandidate candidate, SessionMemory memory) {
        // 把模型候选中的内容复制到数据库实体。
        memory.setMemoryKey(candidate.getMemoryKey());
        memory.setMemoryTopic(candidate.getMemoryTopic());
        memory.setMemorySummary(candidate.getMemorySummary());
        memory.setMemoryContent(candidate.getMemoryContent());
    }

    private boolean sameContent(MemoryCandidate candidate,
                                 String topic,
                                 String summary,
                                 String content) {
        // 比较主题、摘要和正文，判断新增是否只是重复记录。
        return Objects.equals(candidate.getMemoryTopic(), topic)
                && Objects.equals(candidate.getMemorySummary(), summary)
                && Objects.equals(candidate.getMemoryContent(), content);
    }

    private void logSave(String scope, Long ownerId, MemoryCandidate candidate) {
        // 只记录字段长度，不记录完整记忆正文。
        log.info("新增{}成功，ownerId={}，memoryKey={}，summaryCharacters={}，contentCharacters={}",
                scope,
                ownerId,
                candidate.getMemoryKey(),
                safeLength(candidate.getMemorySummary()),
                safeLength(candidate.getMemoryContent()));
    }

    private void requirePositiveId(Long id, String fieldName) {
        // 用户和会话 ID 必须是正数。
        if (id == null || id <= 0) {
            throw new ClientDataErrorException(fieldName + "必须大于 0");
        }
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
