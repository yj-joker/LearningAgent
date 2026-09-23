package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.LearningSessionSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LearningSessionSummaryRepository {

    // 新摘要使用插入而不是覆盖，旧摘要保留用于审计和后续排查。
    @Insert("INSERT INTO learning_session_summaries " +
            "(session_id, summary_content, covered_until_message_id, created_at) " +
            "VALUES (#{summary.sessionId}, #{summary.summaryContent}, " +
            "#{summary.coveredUntilMessageId}, #{summary.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "summary.id", keyColumn = "id")
    int save(@Param("summary") LearningSessionSummary summary);

    // 只取最新摘要；它代表当前会话已经压缩过的最完整历史视图。
    @Select("SELECT id, session_id AS sessionId, summary_content AS summaryContent, " +
            "covered_until_message_id AS coveredUntilMessageId, created_at AS createdAt " +
            "FROM learning_session_summaries " +
            "WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT 1")
    LearningSessionSummary findLatestBySessionId(@Param("sessionId") Long sessionId);
}
