package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.SessionMemory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
// 会话结构化记忆的数据访问接口；查询范围始终包含 sessionId。
public interface SessionMemoryRepository {

    // 保存一条会话级记忆；同一会话中的 memoryKey 只能有一条有效记录。
    @Insert("INSERT INTO session_memories " +
            "(session_id, memory_key, memory_topic, memory_summary, memory_content, status, created_at, updated_at) " +
            "VALUES (#{memory.sessionId}, #{memory.memoryKey}, #{memory.memoryTopic}, " +
            "#{memory.memorySummary}, #{memory.memoryContent}, #{memory.status}, " +
            "#{memory.createdAt}, #{memory.updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "memory.id", keyColumn = "id")
    int save(@Param("memory") SessionMemory memory);

    // 更新当前会话的记忆内容，不改变创建时间和主键。
    @Update("UPDATE session_memories SET memory_key = #{memory.memoryKey}, " +
            "memory_topic = #{memory.memoryTopic}, memory_summary = #{memory.memorySummary}, " +
            "memory_content = #{memory.memoryContent}, updated_at = #{memory.updatedAt} " +
            "WHERE id = #{memory.id} AND session_id = #{memory.sessionId} AND status = 'ACTIVE'")
    int update(@Param("memory") SessionMemory memory);

    // 索引阶段只读取摘要，避免每次请求加载所有正文。
    @Select("SELECT id, session_id AS sessionId, memory_key AS memoryKey, memory_topic AS memoryTopic, " +
            "memory_summary AS memorySummary, status, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM session_memories WHERE session_id = #{sessionId} AND status = 'ACTIVE' " +
            "ORDER BY updated_at DESC, id DESC")
    List<SessionMemory> findActiveIndexBySessionId(@Param("sessionId") Long sessionId);

    // 按会话范围召回正文，防止错误读取其他会话的记忆。
    @Select("SELECT id, session_id AS sessionId, memory_key AS memoryKey, memory_topic AS memoryTopic, " +
            "memory_summary AS memorySummary, memory_content AS memoryContent, status, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM session_memories WHERE id = #{memoryId} AND session_id = #{sessionId} " +
            "AND status = 'ACTIVE'")
    SessionMemory findActiveById(@Param("sessionId") Long sessionId, @Param("memoryId") Long memoryId);

    // 生命周期操作按会话和稳定 key 定位，防止误更新其他会话的记忆。
    @Select("SELECT id, session_id AS sessionId, memory_key AS memoryKey, memory_topic AS memoryTopic, " +
            "memory_summary AS memorySummary, memory_content AS memoryContent, status, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM session_memories WHERE session_id = #{sessionId} AND memory_key = #{memoryKey} " +
            "AND status = 'ACTIVE'")
    SessionMemory findActiveByKey(@Param("sessionId") Long sessionId, @Param("memoryKey") String memoryKey);

    // 会话记忆同样使用假删除，避免直接丢失学习过程中的结构化事实。
    @Update("UPDATE session_memories SET status = 'DELETED', updated_at = #{updatedAt} " +
            "WHERE id = #{memoryId} AND session_id = #{sessionId} AND status = 'ACTIVE'")
    int softDelete(@Param("sessionId") Long sessionId,
                   @Param("memoryId") Long memoryId,
                   @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
