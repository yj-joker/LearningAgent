package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.Chapters;
import lombok.NonNull;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChaptersRepository {
    // 批量保存章节信息
    int saveAll(@Param("chaptersList")List<Chapters> chaptersList);
    // 根据课程ID获取所有章节列表
    @Select("SELECT * FROM chapters WHERE course_id = #{courseId} ORDER BY sort_order")
    List<Chapters> getChaptersByCourseId(@Param("courseId") Long courseId);
    // 修改章节信息
    int updateAll(@Param("chaptersList") List<Chapters> chaptersList);
    // 根据ID列表删除章节信息
    int deleteChaptersByIds(@Param("ids") @NonNull List<Long> ids);
    // 根据对应的ids获取章节
    List<Chapters> getChaptersByIds(@Param("ids") @NonNull List<Long> ids);

}
