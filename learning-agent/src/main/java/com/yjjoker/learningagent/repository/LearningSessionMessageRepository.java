package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.LearningSessionMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LearningSessionMessageRepository {

    // 保存一条消息；工具调用 JSON 为空时，MySQL 会保存为 NULL。
    @Insert("INSERT INTO learning_session_messages " +
            "(session_id, role, content, tool_calls, tool_call_id, created_at) " +
            "VALUES (#{message.sessionId}, #{message.role}, #{message.content}, " +
            "#{message.toolCallsJson}, #{message.toolCallId}, #{message.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "message.id", keyColumn = "id")
    int save(@Param("message") LearningSessionMessage message);

    // id 表示消息写入顺序，按它升序读取才能还原完整对话。
    @Select("SELECT id, session_id AS sessionId, role, content, " +
            "tool_calls AS toolCallsJson, tool_call_id AS toolCallId, created_at AS createdAt " +
            "FROM learning_session_messages WHERE session_id = #{sessionId} ORDER BY id")
    List<LearningSessionMessage> findBySessionId(@Param("sessionId") Long sessionId);

    // 恢复工具结果时只允许查询当前会话中对应调用 ID 的 TOOL 消息。
    @Select("SELECT id, session_id AS sessionId, role, content, " +
            "tool_calls AS toolCallsJson, tool_call_id AS toolCallId, created_at AS createdAt " +
            "FROM learning_session_messages " +
            "WHERE session_id = #{sessionId} AND role = 'TOOL' AND tool_call_id = #{toolCallId} " +
            "ORDER BY id DESC LIMIT 1")
    LearningSessionMessage findToolResult(@Param("sessionId") Long sessionId,
                                          @Param("toolCallId") String toolCallId);
}
