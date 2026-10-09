package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.Courses;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.vo.KnowledgePointRelationContext;

@Mapper
public interface CoursesRepository {
    //查询课程
    @Select("select id, user_id as userId, course_name as courseName, " +
            "difficulty_level as difficultyLevel, publisher_id as publisherId, " +
            "course_type as courseType, learning_outline as learningOutline, " +
            "created_at as createdAt, updated_at as updatedAt " +
            "from courses where id = #{id}")
    Courses findCourseById(@Param("id") Long id);

    // 当前用户课程列表不依赖本地活动记录；按更新时间倒序，编号用于稳定处理同一时间。
    @Select("select id, user_id as userId, course_name as courseName, " +
            "difficulty_level as difficultyLevel, publisher_id as publisherId, " +
            "course_type as courseType, learning_outline as learningOutline, " +
            "created_at as createdAt, updated_at as updatedAt " +
            "from courses where user_id = #{userId} order by updated_at desc, id desc")
    List<Courses> findCoursesByUserId(@Param("userId") Long userId);

    // 编辑直链在 SQL 内同时约束课程和所有者，防止先读取别人正文再做权限检查。
    @Select("select id, user_id as userId, course_name as courseName, " +
            "difficulty_level as difficultyLevel, publisher_id as publisherId, " +
            "course_type as courseType, learning_outline as learningOutline, " +
            "created_at as createdAt, updated_at as updatedAt " +
            "from courses where id = #{courseId} and user_id = #{userId}")
    Courses findCourseByIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    // 批量查询供既有课程权限与知识点流程复用。
    @Select({
            "<script>",
            "select id, user_id as userId, course_name as courseName,",
            "difficulty_level as difficultyLevel, publisher_id as publisherId,",
            "course_type as courseType, learning_outline as learningOutline,",
            "created_at as createdAt, updated_at as updatedAt",
            "from courses where id in",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    List<Courses> findCoursesByIds(@Param("ids") Collection<Long> ids);
    // 只有旧状态仍为 expectedStatus 才更新；并发状态变更只有一个请求能成功。
    @Update("update courses set course_type = #{targetStatus}, updated_at = #{updatedAt} " +
            "where id = #{courseId} and course_type = #{expectedStatus}")
    int updateCourseStatus(@Param("courseId") Long courseId,
                                       @Param("expectedStatus") CoursesTypeEnum expectedStatus,
                                       @Param("targetStatus") CoursesTypeEnum targetStatus,
                                       @Param("updatedAt") LocalDateTime updatedAt);
    //创建课程
    @Insert("insert into courses " +
            "(user_id, course_name, difficulty_level, publisher_id, course_type, " +
            "learning_outline, created_at, updated_at) " +
            "values (#{userId}, #{courseName}, #{difficultyLevel}, #{publisherId}, " +
            "#{courseType}, #{learningOutline}, #{createdAt}, #{updatedAt})")
    //生成课程id, 并返回
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int createCourse(Courses courses);

    // 根据关系两端的知识点 ID，一次查询两个知识点对应的课程、所有者和课程状态。
    KnowledgePointRelationContext findKnowledgePointRelationContext(
            @Param("fromPointId") Long fromPointId,
            @Param("toPointId") Long toPointId);
}
