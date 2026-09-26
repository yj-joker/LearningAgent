package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;

import java.util.List;

// 管理结构化记忆的保存、索引加载和正文召回，不负责自动提取记忆。
public interface StructuredMemoryService {

    // 保存可以跨学习会话使用的用户长期记忆。
    UserMemory saveUserMemory(UserMemory memory);

    // 更新同一条长期记忆，避免同一个 memoryKey 不断插入重复记录。
    UserMemory updateUserMemory(UserMemory memory);

    // 保存只属于当前学习会话的结构化记忆。
    SessionMemory saveSessionMemory(SessionMemory memory);

    // 更新同一条会话记忆，保留原主键和创建时间。
    SessionMemory updateSessionMemory(SessionMemory memory);

    // 只加载长期记忆索引，不读取完整正文。
    List<UserMemory> loadUserMemoryIndex(Long userId);

    // 只加载当前会话的记忆索引，不读取完整正文。
    List<SessionMemory> loadSessionMemoryIndex(Long sessionId);

    // 根据用户范围按需召回一条长期记忆正文。
    UserMemory recallUserMemory(Long userId, Long memoryId);

    // 按稳定 memoryKey 查找用户范围内的有效记忆，用于生命周期更新和删除。
    UserMemory findActiveUserMemoryByKey(Long userId, String memoryKey);

    // 根据会话范围按需召回一条会话记忆正文。
    SessionMemory recallSessionMemory(Long sessionId, Long memoryId);

    // 按稳定 memoryKey 查找当前会话内的有效记忆，用于生命周期更新和删除。
    SessionMemory findActiveSessionMemoryByKey(Long sessionId, String memoryKey);

    // 假删除一条用户长期记忆。
    void deleteUserMemory(Long userId, Long memoryId);

    // 假删除一条会话级记忆。
    void deleteSessionMemory(Long sessionId, Long memoryId);
}
