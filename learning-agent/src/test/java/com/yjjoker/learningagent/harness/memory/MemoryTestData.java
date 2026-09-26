package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.harness.memory.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// 为目标识别测试准备固定数据，不访问用户的真实数据库。
final class MemoryTestData {
    static final Long USER_ID = 20L;
    static final Long SESSION_ID = 10L;

    // 测试数据只通过静态方法创建。
    private MemoryTestData() {
    }

    // 创建有效的长期记忆，固定更新时间用于检查旧快照。
    static UserMemory user(long id, String key, String content) {
        UserMemory memory = new UserMemory();
        memory.setId(id);
        memory.setUserId(USER_ID);
        memory.setMemoryKey(key);
        memory.setMemoryTopic("运动偏好");
        memory.setMemorySummary(content);
        memory.setMemoryContent(content);
        memory.setUpdatedAt(LocalDateTime.of(2026, 9, 26, 10, 0));
        return memory;
    }

    // 创建只属于当前会话的记忆，验证两类索引不会混用。
    static SessionMemory session(long id, String key, String content) {
        SessionMemory memory = new SessionMemory();
        memory.setId(id);
        memory.setSessionId(SESSION_ID);
        memory.setMemoryKey(key);
        memory.setMemoryTopic("当前任务");
        memory.setMemorySummary(content);
        memory.setMemoryContent(content);
        memory.setUpdatedAt(LocalDateTime.of(2026, 9, 26, 10, 0));
        return memory;
    }

    // 为本次测试建立独立的引用表，顺序与输入索引一致。
    static MemoryExtractionContext context(List<UserMemory> users, List<SessionMemory> sessions) {
        return new MemoryExtractionContext(USER_ID, SESSION_ID, new MemoryIndexSnapshot(users, sessions));
    }

    // 没有旧记忆时仍传入用户和会话范围。
    static MemoryExtractionContext emptyContext() {
        return context(List.of(), List.of());
    }

    // 创建模型候选，修改和删除不提供 memoryKey。
    static MemoryCandidate candidate(MemoryOperation operation, MemoryScope scope,
                                     List<String> refs, String evidence, String key, String content) {
        MemoryCandidate candidate = new MemoryCandidate();
        candidate.setOperation(operation);
        candidate.setScope(scope);
        candidate.setTargetMemoryRefs(refs);
        candidate.setUserEvidence(evidence);
        candidate.setMemoryKey(key);
        if (operation != MemoryOperation.DELETE) {
            candidate.setMemoryTopic("用户事实");
            candidate.setMemorySummary(content);
            candidate.setMemoryContent(content);
        }
        return candidate;
    }

    // 把候选转换为模型实际返回的 JSON，测试真实解析流程。
    static String response(MemoryCandidate... candidates) {
        return new JsonMapper().writeValueAsString(Map.of("memories", List.of(candidates)));
    }
}
