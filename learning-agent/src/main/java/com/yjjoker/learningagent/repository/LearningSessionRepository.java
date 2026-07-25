package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.LearningSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

@Mapper
public interface LearningSessionRepository {
    // 创建学习会话
    @Insert("insert into learning_sessions " +
            "(course_id, user_id, status, session_title, created_at, updated_at) " +
            "values (#{courseId}, #{userId}, #{status}, #{sessionTitle}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int createSession(LearningSession learningSession);

    // 根据id查询学习会话
    @Select("select id, course_id as courseId, user_id as userId, " +
            "session_title as sessionTitle, status, created_at as createdAt, " +
            "updated_at as updatedAt from learning_sessions where id = #{sessionId}")
    Optional<LearningSession> findSessionById(@Param("sessionId") Long sessionId);

    // 完成学习会话
    @Update("update learning_sessions set status = #{status}, updated_at = #{updatedAt} " +
            "where id = #{id}")
    int updateSession(LearningSession learningSession);
}
