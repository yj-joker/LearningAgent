package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.Courses;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;
@Mapper
public interface CoursesRepository {
    //查询课程
    @Select("select id, user_id as userId, course_name as courseName, " +
            "difficulty_level as difficultyLevel, publisher_id as publisherId, " +
            "course_type as courseType, learning_outline as learningOutline, " +
            "created_at as createdAt, updated_at as updatedAt " +
            "from courses where id = #{id}")
    Courses findCourseById(@Param("id") Long id);
    //发布课程
    @Update("update courses set course_type = 'PUBLIC',update_at=#{updateAt} where id = #{id}")
    int publishCourse(Courses courses);
    //创建课程
    @Insert("insert into courses " +
            "(user_id, course_name, difficulty_level, publisher_id, course_type, " +
            "learning_outline, created_at, updated_at) " +
            "values (#{userId}, #{courseName}, #{difficultyLevel}, #{publisherId}, " +
            "#{courseType}, #{learningOutline}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int createCourse(Courses courses);
}
