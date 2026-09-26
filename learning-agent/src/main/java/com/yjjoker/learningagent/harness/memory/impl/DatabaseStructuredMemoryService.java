package com.yjjoker.learningagent.harness.memory.impl;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
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
    // 保存一条新的用户长期记忆。
    public UserMemory saveUserMemory(UserMemory memory) {
        // 检查记忆字段是否完整。
        validateUserMemory(memory);
        // 新记忆从 ACTIVE 状态开始。
        LocalDateTime now = LocalDateTime.now();
        memory.setStatus(com.yjjoker.learningagent.projectenum.MemoryStatusEnum.ACTIVE);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        // 数据库只接受一条成功插入的记录。
        if (userMemoryRepository.save(memory) != 1) {
            throw new LearningAgentServiceException("保存用户长期记忆失败，请稍后重试");
        }
        log.info("保存用户长期记忆成功，userId={}，memoryKey={}，summaryCharacters={}",
                memory.getUserId(), memory.getMemoryKey(), safeLength(memory.getMemorySummary()));
        return memory;
    }

    @Override
    @Transactional
    // 更新一条已有的用户长期记忆。
    public UserMemory updateUserMemory(UserMemory memory) {
        // 检查内容和主键，避免更新错误记录。
        validateUserMemory(memory);
        if (memory.getId() == null || memory.getId() <= 0) {
            throw new ClientDataErrorException("长期记忆 ID 必须大于 0");
        }
        memory.setUpdatedAt(LocalDateTime.now());
        // SQL 同时检查 ID、用户 ID 和 ACTIVE 状态。
        if (userMemoryRepository.update(memory) != 1) {
            throw new NotFountException("有效的用户长期记忆不存在");
        }
        log.info("更新用户长期记忆成功，userId={}，memoryId={}，summaryCharacters={}",
                memory.getUserId(), memory.getId(), safeLength(memory.getMemorySummary()));
        return memory;
    }

    @Override
    @Transactional
    // 保存一条新的会话记忆。
    public SessionMemory saveSessionMemory(SessionMemory memory) {
        // 检查会话归属和记忆字段。
        validateSessionMemory(memory);
        LocalDateTime now = LocalDateTime.now();
        memory.setStatus(com.yjjoker.learningagent.projectenum.MemoryStatusEnum.ACTIVE);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        // 数据库只接受一条成功插入的记录。
        if (sessionMemoryRepository.save(memory) != 1) {
            throw new LearningAgentServiceException("保存会话记忆失败，请稍后重试");
        }
        log.info("保存会话结构化记忆成功，sessionId={}，memoryKey={}，summaryCharacters={}",
                memory.getSessionId(), memory.getMemoryKey(), safeLength(memory.getMemorySummary()));
        return memory;
    }

    @Override
    @Transactional
    // 更新一条已有的会话记忆。
    public SessionMemory updateSessionMemory(SessionMemory memory) {
        // 检查内容和主键，避免更新错误记录。
        validateSessionMemory(memory);
        if (memory.getId() == null || memory.getId() <= 0) {
            throw new ClientDataErrorException("会话记忆 ID 必须大于 0");
        }
        memory.setUpdatedAt(LocalDateTime.now());
        // SQL 同时检查 ID、会话 ID 和 ACTIVE 状态。
        if (sessionMemoryRepository.update(memory) != 1) {
            throw new NotFountException("有效的会话记忆不存在");
        }
        log.info("更新会话结构化记忆成功，sessionId={}，memoryId={}，summaryCharacters={}",
                memory.getSessionId(), memory.getId(), safeLength(memory.getMemorySummary()));
        return memory;
    }

    @Override
    // 加载用户记忆索引，只读取摘要，不读取正文。
    public List<UserMemory> loadUserMemoryIndex(Long userId) {
        // 先检查用户 ID，再访问数据库。
        requirePositiveId(userId, "用户 ID");
        List<UserMemory> index = userMemoryRepository.findActiveIndexByUserId(userId);
        log.info("加载用户记忆索引成功，userId={}，memoryCount={}", userId, index.size());
        return index;
    }

    @Override
    // 加载当前会话记忆索引，只读取摘要，不读取正文。
    public List<SessionMemory> loadSessionMemoryIndex(Long sessionId) {
        // 先检查会话 ID，再访问数据库。
        requirePositiveId(sessionId, "会话 ID");
        List<SessionMemory> index = sessionMemoryRepository.findActiveIndexBySessionId(sessionId);
        log.info("加载会话记忆索引成功，sessionId={}，memoryCount={}", sessionId, index.size());
        return index;
    }

    @Override
    // 按用户和记忆 ID 召回一条长期记忆正文。
    public UserMemory recallUserMemory(Long userId, Long memoryId) {
        // 先检查两个 ID，避免跨用户读取。
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
    // 按会话和 memoryKey 查找当前有效的长期记忆。
    public UserMemory findActiveUserMemoryByKey(Long userId, String memoryKey) {
        requirePositiveId(userId, "用户 ID");
        requireMemoryKey(memoryKey);
        UserMemory memory = userMemoryRepository.findActiveByKey(userId, memoryKey);
        log.info("按 memoryKey 查询用户长期记忆，userId={}，memoryKey={}，found={}",
                userId, memoryKey, memory != null);
        return memory;
    }

    @Override
    // 按会话和记忆 ID 召回一条会话记忆正文。
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
    // 按会话和 memoryKey 查找当前有效的会话记忆。
    public SessionMemory findActiveSessionMemoryByKey(Long sessionId, String memoryKey) {
        requirePositiveId(sessionId, "会话 ID");
        requireMemoryKey(memoryKey);
        SessionMemory memory = sessionMemoryRepository.findActiveByKey(sessionId, memoryKey);
        log.info("按 memoryKey 查询会话记忆，sessionId={}，memoryKey={}，found={}",
                sessionId, memoryKey, memory != null);
        return memory;
    }

    @Override
    @Transactional
    // 将用户长期记忆标记为 DELETED。
    public void deleteUserMemory(Long userId, Long memoryId) {
        requirePositiveId(userId, "用户 ID");
        requirePositiveId(memoryId, "记忆 ID");
        // 只修改状态，不删除正文。
        int affectedRows = userMemoryRepository.softDelete(userId, memoryId, LocalDateTime.now());
        if (affectedRows != 1) {
            throw new NotFountException("有效的用户长期记忆不存在");
        }
        log.info("用户长期记忆假删除成功，userId={}，memoryId={}", userId, memoryId);
    }

    @Override
    @Transactional
    // 将会话记忆标记为 DELETED。
    public void deleteSessionMemory(Long sessionId, Long memoryId) {
        requirePositiveId(sessionId, "会话 ID");
        requirePositiveId(memoryId, "记忆 ID");
        // 只修改状态，不删除正文。
        int affectedRows = sessionMemoryRepository.softDelete(sessionId, memoryId, LocalDateTime.now());
        if (affectedRows != 1) {
            throw new NotFountException("有效的会话记忆不存在");
        }
        log.info("会话记忆假删除成功，sessionId={}，memoryId={}", sessionId, memoryId);
    }

    // 长期记忆至少需要稳定 key、主题、索引摘要和完整正文。
    private void validateUserMemory(UserMemory memory) {
        // 用户长期记忆必须有用户 ID 和完整内容。
        if (memory == null) {
            throw new ClientDataErrorException("用户长期记忆不能为空");
        }
        requirePositiveId(memory.getUserId(), "用户 ID");
        validateMemoryFields(memory.getMemoryKey(), memory.getMemoryTopic(),
                memory.getMemorySummary(), memory.getMemoryContent());
    }

    // 会话记忆与长期记忆使用相同的字段规则，只是归属范围不同。
    private void validateSessionMemory(SessionMemory memory) {
        // 会话记忆必须有会话 ID 和完整内容。
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
        // 四个字段都为空时不能保存记忆。
        if (isBlank(memoryKey) || isBlank(memoryTopic)
                || isBlank(memorySummary) || isBlank(memoryContent)) {
            throw new ClientDataErrorException("记忆名称、主题、摘要和正文不能为空");
        }
    }

    private void requireMemoryKey(String memoryKey) {
        // memoryKey 是新增、更新和删除的定位依据。
        if (memoryKey == null || memoryKey.isBlank()) {
            throw new ClientDataErrorException("memoryKey 不能为空");
        }
    }

    private void requirePositiveId(Long id, String fieldName) {
        // 归属 ID 和主键必须是正数。
        if (id == null || id <= 0) {
            throw new ClientDataErrorException(fieldName + "必须大于 0");
        }
    }

    private boolean isBlank(String value) {
        // 判断字符串为空或只有空格。
        return value == null || value.isBlank();
    }

    private int safeLength(String value) {
        // 日志只记录文本长度，避免输出完整正文。
        return value == null ? 0 : value.length();
    }
}
