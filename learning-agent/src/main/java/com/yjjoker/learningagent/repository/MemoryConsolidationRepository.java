package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationState;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

// 只记录整理进度；记忆正文仍保存在原有的两张表中。
@Mapper
public interface MemoryConsolidationRepository {
    // 第一次把已有记忆数量计入待整理工作；重复调用不重置进度。
    @Insert("INSERT INTO memory_consolidation_state (scope, owner_id, change_count, processed_count) "
            + "VALUES (#{scope}, #{ownerId}, #{initialCount}, 0) ON DUPLICATE KEY UPDATE owner_id = owner_id")
    void initialize(@Param("scope") MemoryScope scope, @Param("ownerId") Long ownerId,
                    @Param("initialCount") long initialCount);

    // 与实际记忆变更一起提交；事务回滚时计数也回滚。
    @Update("UPDATE memory_consolidation_state SET change_count = change_count + #{amount} "
            + "WHERE scope = #{scope} AND owner_id = #{ownerId}")
    void addChanges(@Param("scope") MemoryScope scope, @Param("ownerId") Long ownerId, @Param("amount") long amount);

    // 请求模型前只读取进度，不持有数据库锁。
    @Select("SELECT scope, owner_id AS ownerId, change_count AS changeCount, processed_count AS processedCount "
            + "FROM memory_consolidation_state WHERE scope = #{scope} AND owner_id = #{ownerId}")
    MemoryConsolidationState find(@Param("scope") MemoryScope scope, @Param("ownerId") Long ownerId);

    // 保存方案时锁住进度，防止两个请求重复提交同一轮整理。
    @Select("SELECT scope, owner_id AS ownerId, change_count AS changeCount, processed_count AS processedCount "
            + "FROM memory_consolidation_state WHERE scope = #{scope} AND owner_id = #{ownerId} FOR UPDATE")
    MemoryConsolidationState lock(@Param("scope") MemoryScope scope, @Param("ownerId") Long ownerId);

    // 只确认模型检查过的次数；出现新变更时不能清掉这些未处理的工作。
    @Update("UPDATE memory_consolidation_state SET processed_count = #{changeCount} "
            + "WHERE scope = #{scope} AND owner_id = #{ownerId} "
            + "AND change_count = #{changeCount} AND processed_count = #{processedCount}")
    int markProcessed(@Param("scope") MemoryScope scope, @Param("ownerId") Long ownerId,
                      @Param("changeCount") long changeCount, @Param("processedCount") long processedCount);
}
