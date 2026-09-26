package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.UserMemory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
// 用户长期记忆的数据访问接口；查询范围始终包含 userId。
public interface UserMemoryRepository {

    // 保存一条长期记忆；memoryKey 在同一用户下保持唯一，便于后续更新。
    @Insert("INSERT INTO user_memories " +
            "(user_id, memory_key, memory_topic, memory_summary, memory_content, status, created_at, updated_at) " +
            "VALUES (#{memory.userId}, #{memory.memoryKey}, #{memory.memoryTopic}, " +
            "#{memory.memorySummary}, #{memory.memoryContent}, #{memory.status}, " +
            "#{memory.createdAt}, #{memory.updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "memory.id", keyColumn = "id")
    int save(@Param("memory") UserMemory memory);

    // 更新正文和索引字段；只允许更新当前用户仍然有效的记忆。
    @Update("UPDATE user_memories SET memory_key = #{memory.memoryKey}, " +
            "memory_topic = #{memory.memoryTopic}, memory_summary = #{memory.memorySummary}, " +
            "memory_content = #{memory.memoryContent}, updated_at = #{memory.updatedAt} " +
            "WHERE id = #{memory.id} AND user_id = #{memory.userId} AND status = 'ACTIVE'")
    int update(@Param("memory") UserMemory memory);

    // 索引查询只读取主题和摘要，不加载完整正文。
    @Select("SELECT id, user_id AS userId, memory_key AS memoryKey, memory_topic AS memoryTopic, " +
            "memory_summary AS memorySummary, status, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM user_memories WHERE user_id = #{userId} AND status = 'ACTIVE' " +
            "ORDER BY updated_at DESC, id DESC")
    List<UserMemory> findActiveIndexByUserId(@Param("userId") Long userId);

    // 只有按需召回时才读取完整正文。
    @Select("SELECT id, user_id AS userId, memory_key AS memoryKey, memory_topic AS memoryTopic, " +
            "memory_summary AS memorySummary, memory_content AS memoryContent, status, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM user_memories WHERE id = #{memoryId} AND user_id = #{userId} " +
            "AND status = 'ACTIVE'")
    UserMemory findActiveById(@Param("userId") Long userId, @Param("memoryId") Long memoryId);

    // 写入事务中锁定目标，防止校验通过后又被其他请求修改。
    @Select("SELECT id, user_id AS userId, memory_key AS memoryKey, memory_topic AS memoryTopic, " +
            "memory_summary AS memorySummary, memory_content AS memoryContent, status, " +
            "created_at AS createdAt, updated_at AS updatedAt FROM user_memories " +
            "WHERE id = #{memoryId} AND user_id = #{userId} AND status = 'ACTIVE' FOR UPDATE")
    UserMemory findActiveByIdForUpdate(@Param("userId") Long userId, @Param("memoryId") Long memoryId);

    // 生命周期操作按用户和稳定 key 定位，不允许跨用户查找。
    @Select("SELECT id, user_id AS userId, memory_key AS memoryKey, memory_topic AS memoryTopic, " +
            "memory_summary AS memorySummary, memory_content AS memoryContent, status, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM user_memories WHERE user_id = #{userId} AND memory_key = #{memoryKey} " +
            "AND status = 'ACTIVE'")
    UserMemory findActiveByKey(@Param("userId") Long userId, @Param("memoryKey") String memoryKey);

    // 假删除只改变状态，保留记忆正文供审计或后续恢复。
    @Update("UPDATE user_memories SET status = 'DELETED', updated_at = #{updatedAt} " +
            "WHERE id = #{memoryId} AND user_id = #{userId} AND status = 'ACTIVE'")
    int softDelete(@Param("userId") Long userId,
                   @Param("memoryId") Long memoryId,
                   @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
