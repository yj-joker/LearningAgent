package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface LearningSessionRepository {
    // 同一张表保存课程与独立会话；独立会话的 courseId 为空，模式显式保存。
    @Insert("insert into learning_sessions " +
            "(course_id, user_id, status, session_title, session_mode, created_at, updated_at) " +
            "values (#{courseId}, #{userId}, #{status}, #{sessionTitle}, " +
            "COALESCE(#{mode}, 'COURSE'), #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int createSession(LearningSession learningSession);

    // 根据id查询学习会话
    @Select("select id, course_id as courseId, user_id as userId, " +
            "session_title as sessionTitle, session_mode as mode, status, created_at as createdAt, " +
            "updated_at as updatedAt from learning_sessions where id = #{sessionId}")
    Optional<LearningSession> findSessionById(@Param("sessionId") Long sessionId);

    // 课程进度初始化时锁住会话，防止并发请求分别写入不同的课程快照。
    @Select("select id, course_id as courseId, user_id as userId, " +
            "session_title as sessionTitle, session_mode as mode, status, created_at as createdAt, " +
            "updated_at as updatedAt from learning_sessions where id = #{sessionId} FOR UPDATE")
    Optional<LearningSession> findSessionForUpdate(@Param("sessionId") Long sessionId);

    // 用户范围直接写进 SQL；包括进行中与已完成会话，假删除数据不返回。
    // LEFT JOIN 保留没有课程或源课程已删除的会话，最新消息时间决定最近活动顺序。
    @Select("SELECT s.id, s.session_title AS sessionTitle, s.status AS sessionStatus, " +
            "s.course_id AS courseId, c.course_name AS courseName, s.session_mode AS mode, " +
            "s.created_at AS createdAt, s.created_at AS createAt, " +
            "GREATEST(s.updated_at, COALESCE(m.last_message_at, s.updated_at)) AS updatedAt, " +
            "GREATEST(s.updated_at, COALESCE(m.last_message_at, s.updated_at)) AS updateAt " +
            "FROM learning_sessions s LEFT JOIN courses c ON c.id = s.course_id " +
            "LEFT JOIN (SELECT messages.session_id, MAX(messages.created_at) AS last_message_at " +
            "FROM learning_session_messages messages " +
            "INNER JOIN learning_sessions owned ON owned.id = messages.session_id " +
            "WHERE owned.user_id = #{userId} GROUP BY messages.session_id) m ON m.session_id = s.id " +
            "WHERE s.user_id = #{userId} AND s.status != 'CANCELED' " +
            "ORDER BY updatedAt DESC, s.id DESC")
    List<LearningSessionVO> findVisibleSessionsByUserId(@Param("userId") Long userId);

    // 完成学习会话
    @Update("update learning_sessions set status = #{status}, updated_at = #{updatedAt} " +
            "where id = #{id}")
    int updateSession(LearningSession learningSession);
}
