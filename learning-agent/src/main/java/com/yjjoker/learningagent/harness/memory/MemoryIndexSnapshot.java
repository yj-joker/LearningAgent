package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;

import java.util.List;

// 保存一次 Agent 请求开始时读取到的两类记忆索引快照。
// 它只负责承载索引，不负责查询正文或决定是否召回。
public class MemoryIndexSnapshot {

    private final List<UserMemory> userMemories;
    private final List<SessionMemory> sessionMemories;

    public MemoryIndexSnapshot(List<UserMemory> userMemories,
                               List<SessionMemory> sessionMemories) {
        // 固定列表结构，防止服务层返回的列表在本轮请求中被增删。
        this.userMemories = List.copyOf(userMemories);
        this.sessionMemories = List.copyOf(sessionMemories);
    }

    public List<UserMemory> getUserMemories() {
        return userMemories;
    }

    public List<SessionMemory> getSessionMemories() {
        return sessionMemories;
    }
}
