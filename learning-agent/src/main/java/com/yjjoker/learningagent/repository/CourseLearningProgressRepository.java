package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.CourseLearningPointProgress;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.yjjoker.learningagent.harness.course.model.CourseProgressProposal;

import java.util.List;

// 读写课程学习进度；课程内容读取和进度写入分开，便于后续审批事务复用。
@Mapper
public interface CourseLearningProgressRepository {
    // 读取一个会话的全部知识点进度，并按初始化时的课程顺序返回。
    @Select("""
            SELECT session_id AS sessionId, course_id AS courseId, chapter_id AS chapterId,
                   knowledge_point_id AS knowledgePointId,
                   chapter_sort_order_snapshot AS chapterSortOrderSnapshot,
                   knowledge_point_sort_order_snapshot AS knowledgePointSortOrderSnapshot,
                   status, evidence_type AS evidenceType,
                   evidence_summary AS evidenceSummary, assessment_reason AS assessmentReason,
                   course_updated_at_snapshot AS courseUpdatedAtSnapshot,
                   chapter_title_snapshot AS chapterTitleSnapshot,
                   knowledge_point_name_snapshot AS knowledgePointNameSnapshot,
                   knowledge_point_description_snapshot AS knowledgePointDescriptionSnapshot,
                   version, created_at AS createdAt, updated_at AS updatedAt
            FROM course_learning_point_progress
            WHERE session_id = #{sessionId}
            ORDER BY chapter_sort_order_snapshot, knowledge_point_sort_order_snapshot,
                     chapter_id, knowledge_point_id
            """)
    List<CourseLearningPointProgress> findBySessionId(@Param("sessionId") Long sessionId);

    // 初始化时只插入缺少的知识点，绝不覆盖已有学习证据。
    @Insert("""
            INSERT INTO course_learning_point_progress
            (session_id, course_id, chapter_id, knowledge_point_id,
             chapter_sort_order_snapshot, knowledge_point_sort_order_snapshot, status,
             course_updated_at_snapshot, chapter_title_snapshot,
             knowledge_point_name_snapshot, knowledge_point_description_snapshot,
             version, created_at, updated_at)
            VALUES (#{sessionId}, #{courseId}, #{chapterId}, #{knowledgePointId},
                    #{chapterSortOrderSnapshot}, #{knowledgePointSortOrderSnapshot},
                    'NOT_STARTED', #{courseUpdatedAtSnapshot}, #{chapterTitleSnapshot},
                    #{knowledgePointNameSnapshot}, #{knowledgePointDescriptionSnapshot},
                    1, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE knowledge_point_id = knowledge_point_id
            """)
    int insertIfAbsent(CourseLearningPointProgress progress);

    // 锁定单个知识点，供后续审批后的状态变更使用。
    @Select("""
            SELECT session_id AS sessionId, course_id AS courseId, chapter_id AS chapterId,
                   knowledge_point_id AS knowledgePointId,
                   chapter_sort_order_snapshot AS chapterSortOrderSnapshot,
                   knowledge_point_sort_order_snapshot AS knowledgePointSortOrderSnapshot,
                   status, evidence_type AS evidenceType,
                   evidence_summary AS evidenceSummary, assessment_reason AS assessmentReason,
                   course_updated_at_snapshot AS courseUpdatedAtSnapshot,
                   chapter_title_snapshot AS chapterTitleSnapshot,
                   knowledge_point_name_snapshot AS knowledgePointNameSnapshot,
                   knowledge_point_description_snapshot AS knowledgePointDescriptionSnapshot,
                   version, created_at AS createdAt, updated_at AS updatedAt
            FROM course_learning_point_progress
            WHERE session_id = #{sessionId} AND knowledge_point_id = #{knowledgePointId}
            FOR UPDATE
            """)
    CourseLearningPointProgress findForUpdate(@Param("sessionId") Long sessionId,
                                               @Param("knowledgePointId") Long knowledgePointId);

    // 固定主键顺序锁定本会话所有进度，保证检查当前知识点和修改状态之间没有并发写入。
    @Select("""
            SELECT session_id AS sessionId, course_id AS courseId, chapter_id AS chapterId,
                   knowledge_point_id AS knowledgePointId,
                   chapter_sort_order_snapshot AS chapterSortOrderSnapshot,
                   knowledge_point_sort_order_snapshot AS knowledgePointSortOrderSnapshot,
                   status, evidence_type AS evidenceType, evidence_summary AS evidenceSummary,
                   assessment_reason AS assessmentReason, course_updated_at_snapshot AS courseUpdatedAtSnapshot,
                   chapter_title_snapshot AS chapterTitleSnapshot,
                   knowledge_point_name_snapshot AS knowledgePointNameSnapshot,
                   knowledge_point_description_snapshot AS knowledgePointDescriptionSnapshot,
                   version, created_at AS createdAt, updated_at AS updatedAt
            FROM course_learning_point_progress WHERE session_id = #{sessionId}
            ORDER BY knowledge_point_id FOR UPDATE
            """)
    List<CourseLearningPointProgress> findAllForUpdate(@Param("sessionId") Long sessionId);

    // 状态、证据和版本一起更新；旧版本申请不能覆盖后来确认的学习结果。
    @Update("""
            UPDATE course_learning_point_progress
            SET status = #{proposal.targetStatus}, evidence_type = #{proposal.evidenceType},
                evidence_summary = #{proposal.evidenceSummary}, assessment_reason = #{proposal.assessmentReason},
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId} AND knowledge_point_id = #{knowledgePointId}
                AND version = #{proposal.expectedVersion}
            """)
    int updateIfVersionMatches(@Param("sessionId") Long sessionId,
                               @Param("knowledgePointId") Long knowledgePointId,
                               @Param("proposal") CourseProgressProposal proposal);
}
