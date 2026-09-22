package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.LearningSessionMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LearningSessionMessageRepository {

    // 保存一条消息；工具调用 JSON 为空时，MySQL 会保存为 NULL。
    @Insert("INSERT INTO learning_session_messages " +
            "(session_id, role, content, context_content, tool_calls, tool_call_id, " +
            "context_replayable, created_at) " +
            "VALUES (#{message.sessionId}, #{message.role}, #{message.content}, " +
            "#{message.contextContent}, #{message.toolCallsJson}, #{message.toolCallId}, " +
            "#{message.contextReplayable}, #{message.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "message.id", keyColumn = "id")
    int save(@Param("message") LearningSessionMessage message);

    // 只加载允许重放的消息；恢复工具轨迹虽然保留在数据库，但不会进入未来上下文。
    @Select("SELECT id, session_id AS sessionId, role, content, context_content AS contextContent, " +
            "tool_calls AS toolCallsJson, tool_call_id AS toolCallId, " +
            "context_replayable AS contextReplayable, created_at AS createdAt " +
            "FROM learning_session_messages " +
            "WHERE session_id = #{sessionId} AND context_replayable = TRUE ORDER BY id")
    List<LearningSessionMessage> findReplayableBySessionId(@Param("sessionId") Long sessionId);

    // 恢复工具结果时只允许查询当前会话中对应调用 ID 的 TOOL 消息。
    @Select("SELECT id, session_id AS sessionId, role, content, context_content AS contextContent, " +
            "tool_calls AS toolCallsJson, tool_call_id AS toolCallId, " +
            "context_replayable AS contextReplayable, created_at AS createdAt " +
            "FROM learning_session_messages " +
            "WHERE session_id = #{sessionId} AND role = 'TOOL' " +
            "AND tool_call_id = #{toolCallId} AND context_replayable = TRUE " +
            "ORDER BY id DESC LIMIT 1")
    LearningSessionMessage findToolResult(@Param("sessionId") Long sessionId,
                                          @Param("toolCallId") String toolCallId);

    // 历史工具结果被压缩时只更新上下文副本，完整原文 content 保持不变。
    @Update("UPDATE learning_session_messages SET context_content = #{contextContent} " +
            "WHERE session_id = #{sessionId} AND role = 'TOOL' " +
            "AND tool_call_id = #{toolCallId} AND context_replayable = TRUE " +
            "AND (context_content IS NULL OR context_content <> #{contextContent})")
    int updateToolContextContent(@Param("sessionId") Long sessionId,
                                 @Param("toolCallId") String toolCallId,
                                 @Param("contextContent") String contextContent);
}
