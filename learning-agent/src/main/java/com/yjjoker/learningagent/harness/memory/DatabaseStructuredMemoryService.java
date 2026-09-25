package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.repository.SessionMemoryRepository;
import com.yjjoker.learningagent.repository.UserMemoryRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// 数据库结构化记忆服务：把“加载索引”和“按需召回正文”明确分成两个步骤。
@Service
@AllArgsConstructor
@Slf4j
public class DatabaseStructuredMemoryService implements StructuredMemoryService {

    // Repository 只负责 SQL；参数校验、状态处理和日志放在服务层。
    private final UserMemoryRepository userMemoryRepository;
    private final SessionMemoryRepository sessionMemoryRepository;

    @Override
    @Transactional
    public UserMemory saveUserMemory(UserMemory memory) {
        validateUserMemory(memory);
        LocalDateTime now = LocalDateTime.now();
        memory.setStatus(com.yjjoker.learningagent.projectenum.MemoryStatusEnum.ACTIVE);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        if (userMemoryRepository.save(memory) != 1) {
            throw new LearningAgentServiceException("保存用户长期记忆失败，请稍后重试");
        }
        log.info("保存用户长期记忆成功，userId={}，memoryKey={}，summaryCharacters={}",
                memory.getUserId(), memory.getMemoryKey(), safeLength(memory.getMemorySummary()));
        return memory;
    }

    @Override
    @Transactional
    public UserMemory updateUserMemory(UserMemory memory) {
        validateUserMemory(memory);
        if (memory.getId() == null || memory.getId() <= 0) {
            throw new ClientDataErrorException("长期记忆 ID 必须大于 0");
        }
        memory.setUpdatedAt(LocalDateTime.now());
        if (userMemoryRepository.update(memory) != 1) {
            throw new NotFountException("有效的用户长期记忆不存在");
        }
        log.info("更新用户长期记忆成功，userId={}，memoryId={}，summaryCharacters={}",
                memory.getUserId(), memory.getId(), safeLength(memory.getMemorySummary()));
        return memory;
    }

    @Override
    @Transactional
    public SessionMemory saveSessionMemory(SessionMemory memory) {
        validateSessionMemory(memory);
        LocalDateTime now = LocalDateTime.now();
        memory.setStatus(com.yjjoker.learningagent.projectenum.MemoryStatusEnum.ACTIVE);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        if (sessionMemoryRepository.save(memory) != 1) {
            throw new LearningAgentServiceException("保存会话记忆失败，请稍后重试");
        }
        log.info("保存会话结构化记忆成功，sessionId={}，memoryKey={}，summaryCharacters={}",
                memory.getSessionId(), memory.getMemoryKey(), safeLength(memory.getMemorySummary()));
        return memory;
    }

    @Override
    @Transactional
    public SessionMemory updateSessionMemory(SessionMemory memory) {
        validateSessionMemory(memory);
        if (memory.getId() == null || memory.getId() <= 0) {
            throw new ClientDataErrorException("会话记忆 ID 必须大于 0");
        }
        memory.setUpdatedAt(LocalDateTime.now());
        if (sessionMemoryRepository.update(memory) != 1) {
            throw new NotFountException("有效的会话记忆不存在");
        }
        log.info("更新会话结构化记忆成功，sessionId={}，memoryId={}，summaryCharacters={}",
                memory.getSessionId(), memory.getId(), safeLength(memory.getMemorySummary()));
        return memory;
    }

    @Override
    public List<UserMemory> loadUserMemoryIndex(Long userId) {
        requirePositiveId(userId, "用户 ID");
        List<UserMemory> index = userMemoryRepository.findActiveIndexByUserId(userId);
        log.info("加载用户记忆索引成功，userId={}，memoryCount={}", userId, index.size());
        return index;
    }

    @Override
    public List<SessionMemory> loadSessionMemoryIndex(Long sessionId) {
        requirePositiveId(sessionId, "会话 ID");
        List<SessionMemory> index = sessionMemoryRepository.findActiveIndexBySessionId(sessionId);
        log.info("加载会话记忆索引成功，sessionId={}，memoryCount={}", sessionId, index.size());
        return index;
    }

    @Override
    public UserMemory recallUserMemory(Long userId, Long memoryId) {
        requirePositiveId(userId, "用户 ID");
        requirePositiveId(memoryId, "记忆 ID");
        UserMemory memory = userMemoryRepository.findActiveById(userId, memoryId);
        if (memory == null) {
            throw new NotFountException("用户长期记忆不存在");
        }
        log.info("召回用户长期记忆正文成功，userId={}，memoryId={}，contentCharacters={}",
                userId, memoryId, safeLength(memory.getMemoryContent()));
        return memory;
    }

    @Override
    public SessionMemory recallSessionMemory(Long sessionId, Long memoryId) {
        requirePositiveId(sessionId, "会话 ID");
        requirePositiveId(memoryId, "记忆 ID");
        SessionMemory memory = sessionMemoryRepository.findActiveById(sessionId, memoryId);
        if (memory == null) {
            throw new NotFountException("会话记忆不存在");
        }
        log.info("召回会话记忆正文成功，sessionId={}，memoryId={}，contentCharacters={}",
                sessionId, memoryId, safeLength(memory.getMemoryContent()));
        return memory;
    }

    @Override
    @Transactional
    public void deleteUserMemory(Long userId, Long memoryId) {
        requirePositiveId(userId, "用户 ID");
        requirePositiveId(memoryId, "记忆 ID");
        int affectedRows = userMemoryRepository.softDelete(userId, memoryId, LocalDateTime.now());
        if (affectedRows != 1) {
            throw new NotFountException("有效的用户长期记忆不存在");
        }
        log.info("用户长期记忆假删除成功，userId={}，memoryId={}", userId, memoryId);
    }

    @Override
    @Transactional
    public void deleteSessionMemory(Long sessionId, Long memoryId) {
        requirePositiveId(sessionId, "会话 ID");
        requirePositiveId(memoryId, "记忆 ID");
        int affectedRows = sessionMemoryRepository.softDelete(sessionId, memoryId, LocalDateTime.now());
        if (affectedRows != 1) {
            throw new NotFountException("有效的会话记忆不存在");
        }
        log.info("会话记忆假删除成功，sessionId={}，memoryId={}", sessionId, memoryId);
    }

    // 长期记忆至少需要稳定 key、主题、索引摘要和完整正文。
    private void validateUserMemory(UserMemory memory) {
        if (memory == null) {
            throw new ClientDataErrorException("用户长期记忆不能为空");
        }
        requirePositiveId(memory.getUserId(), "用户 ID");
        validateMemoryFields(memory.getMemoryKey(), memory.getMemoryTopic(),
                memory.getMemorySummary(), memory.getMemoryContent());
    }

    // 会话记忆与长期记忆使用相同的字段规则，只是归属范围不同。
    private void validateSessionMemory(SessionMemory memory) {
        if (memory == null) {
            throw new ClientDataErrorException("会话记忆不能为空");
        }
        requirePositiveId(memory.getSessionId(), "会话 ID");
        validateMemoryFields(memory.getMemoryKey(), memory.getMemoryTopic(),
                memory.getMemorySummary(), memory.getMemoryContent());
    }

    private void validateMemoryFields(String memoryKey,
                                      String memoryTopic,
                                      String memorySummary,
                                      String memoryContent) {
        if (isBlank(memoryKey) || isBlank(memoryTopic)
                || isBlank(memorySummary) || isBlank(memoryContent)) {
            throw new ClientDataErrorException("记忆名称、主题、摘要和正文不能为空");
        }
    }

    private void requirePositiveId(Long id, String fieldName) {
        if (id == null || id <= 0) {
            throw new ClientDataErrorException(fieldName + "必须大于 0");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
