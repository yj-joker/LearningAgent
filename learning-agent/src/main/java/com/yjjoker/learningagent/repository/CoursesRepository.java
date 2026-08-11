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

@Mapper
public interface CoursesRepository {
    //查询课程
    @Select("select id, user_id as userId, course_name as courseName, " +
            "difficulty_level as difficultyLevel, publisher_id as publisherId, " +
            "course_type as courseType, learning_outline as learningOutline, " +
            "created_at as createdAt, updated_at as updatedAt " +
            "from courses where id = #{id}")
    Courses findCourseById(@Param("id") Long id);

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
    /**
     * 只有数据库中的旧状态仍为 expectedStatus 时才允许更新。
     * 两个请求同时读取同一课程状态时，只有一个能更新成功。
     */
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
}
