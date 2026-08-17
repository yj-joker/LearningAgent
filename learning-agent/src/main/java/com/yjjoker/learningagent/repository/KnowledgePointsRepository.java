package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.KnowledgePoints;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgePointsRepository {

    int saveAll(@Param("knowledgePointsList") List<KnowledgePoints> knowledgePointsList);

    List<KnowledgePoints> findByIds(@Param("ids") List<Long> ids);

    @Select("""
            SELECT id,
                   course_id AS courseId,
                   chapter_id AS chapterId,
                   name,
                   sort_order AS sortOrder,
                   description,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM knowledge_points
            WHERE chapter_id = #{chapterId}
            ORDER BY sort_order
            """)
    List<KnowledgePoints> findByChapterId(@Param("chapterId") Long chapterId);

    int updateAll(@Param("knowledgePoints") List<KnowledgePoints> knowledgePoints);

    int deleteAll(@Param("ids") List<Long> ids);

    int deleteByChapterId(@Param("chapterIds") List<Long> chapterIds);

    //TODO 两个根据课程id查找该课程所以知识点关系的知识点sql语句
    List<KnowledgePoints> getConfusableKnowledgePointsByCourseId(@Param("courseId") Long courseId);

    List<KnowledgePoints> getPrerequisiteKnowledgePointsByCourseId(@Param("courseId") Long courseId);

}
