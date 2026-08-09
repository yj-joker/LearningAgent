package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.ChaptersDTO;
import com.yjjoker.learningagent.entity.Chapters;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.exception.*;
import com.yjjoker.learningagent.repository.ChaptersRepository;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.service.ChaptersService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import com.yjjoker.learningagent.vo.ChaptersVO;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.*;

@Service
@AllArgsConstructor
@Slf4j
public class ChaptersServiceImpl implements ChaptersService {
    private static final long MYSQL_UNSIGNED_INT_MAX = 4_294_967_295L;

    private final ChaptersRepository chaptersRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final CoursesRepository coursesRepository;

    //批量添加章节
    @Override
    public List<ChaptersVO> createChapters(@NonNull List<ChaptersDTO> chaptersDTOList) {
        if (chaptersDTOList.isEmpty()) {
            return List.of();
        }
        List<Chapters> chaptersList = dtoToEntity(chaptersDTOList);
        int result;
        try {
            result = chaptersRepository.saveAll(chaptersList);
        } catch (DuplicateKeyException e) {
            throw translateDuplicateKeyException(e);
        } catch (DataIntegrityViolationException e) {
            log.warn("创建章节时违反数据完整性约束", e);
            throw new DataIllegalException("章节数据不符合数据库约束");
        } catch (Exception e) {
            log.error("添加章节失败", e);
            throw new LearningAgentServiceException("添加章节失败，请稍后再试");
        }
        if (chaptersList.size() != result) {
            throw new LearningAgentServiceException("添加章节失败，请稍后再试");
        }
        return getChaptersVOList(chaptersList);
    }

    //根据课程ID获取章节列表
    @Override
    public List<ChaptersVO> getChaptersByCourseId(@NonNull Long courseId) {
        checkUserOwnsCourse(courseId);
        List<Chapters> chaptersList;
        try {
            chaptersList = chaptersRepository.getChaptersByCourseId(courseId);
        } catch (Exception e) {
            throw new LearningAgentServiceException("获取章节列表失败，请稍后再试");
        }
        return getChaptersVOList(chaptersList);
    }

    //根据章节ID删除章节
    @Override
    @Transactional
    public void deleteChaptersByIds(@NonNull List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        //检验数据格式
        Set<Long> uniqueIds = new HashSet<>();
        for (Long id : ids) {
            if (id == null || !uniqueIds.add(id)) {
                throw new DataIllegalException("章节 ID 不能为空且不能重复");
            }
        }
        //用户是否有权限处理这些章节
        checkUserCanHandle(ids);
        int affectedRows;
        try {
            affectedRows = chaptersRepository.deleteChaptersByIds(ids);
        } catch (DataIntegrityViolationException e) {
            log.warn("删除章节时违反数据完整性约束", e);
            throw new DataIllegalException("章节仍被其他数据引用，无法删除");
        } catch (Exception e) {
            log.error("删除章节失败", e);
            throw new LearningAgentServiceException("删除章节失败，请稍后再试");
        }
        if (affectedRows != ids.size()) {
            throw new NotFountException("部分章节不存在");
        }
        //TODO删除章节下的知识点
        log.info("删除章节成功");
    }

    //修改章节，允许修改对应的章节的顺序和章节标题
    @Transactional
    @Override
    public List<ChaptersVO> updateChapters(@NonNull List<ChaptersDTO> chaptersDTOList) {
        if (chaptersDTOList.isEmpty()) {
            return List.of();
        }
        List<Chapters> chaptersList = dtoToUpdateEntities(chaptersDTOList);
        Set<Long> chapterIds = getChapterIds(chaptersList);
        Long actualCourseId = checkUserCanHandle(new ArrayList<>(chapterIds));
        for (Chapters chapter : chaptersList) {
            if (!Objects.equals(chapter.getCourseId(), actualCourseId)) {
                throw new DataIllegalException("章节 ID 与课程 ID 不匹配");
            }
        }
        int affectedRows;
        try {
            //更新数据
             affectedRows = chaptersRepository.updateAll(chaptersList);
        } catch (DuplicateKeyException e) {
            throw translateDuplicateKeyException(e);
        } catch (DataIntegrityViolationException e) {
            log.warn("修改章节时违反数据完整性约束", e);
            throw new DataIllegalException("章节数据不符合数据库约束");
        } catch (Exception e) {
            log.error("修改章节失败", e);
            throw new LearningAgentServiceException("修改章节失败，请稍后再试");
        }
        if (affectedRows != chaptersList.size()) {
            throw new NotFountException("部分章节不存在或未被更新");
        }
        log.info("修改章节成功");
        return getChaptersVOList(chaptersList);
    }

    //获取章节ID集合
    private  Set<Long> getChapterIds(List<Chapters> chaptersList) {
        Set<Long> chapterIds = new HashSet<>();
        // 规范数据格式
        for (Chapters chapter : chaptersList) {
            if (chapter.getId() == null || chapter.getCourseId() == null) {
                throw new NotFountException("章节 ID 和课程 ID 不能为空");
            }
            if (!chapterIds.add(chapter.getId())) {
                throw new DataIllegalException("同一个章节不能在一次请求中重复更新");
            }
            if (chapter.getSortOrder() != null && (chapter.getSortOrder() < 0 || chapter.getSortOrder() > MYSQL_UNSIGNED_INT_MAX)) {
                throw new ExceededDatabaseLimitException("章节排序值超出数据允许范围，请刷新章节列表后重试");
            }
        }
        return chapterIds;
    }

    //检查用户是否有权限处理这些章节
    private Long checkUserCanHandle(List<Long> ids) {
        //根据章节ID获取章节列表
        List<Chapters> chaptersByCourseId = chaptersRepository.getChaptersByIds(ids);
        if (chaptersByCourseId.isEmpty()||ids.size()!=chaptersByCourseId.size()) {
            throw new NotFountException("部分章节不存在");
        }
        //判断需要处理的全部章节的课程id是否一致
        Long courseId = getCommonCourseId(chaptersByCourseId);
        log.info("操作的章节属于同一课程");
        //判断该课程是否属于当前用户
        checkUserOwnsCourse(courseId);
        return courseId;
    }

    //检查该课程是否属于当前用户
    private void checkUserOwnsCourse(Long courseId) {
        Courses courseById = coursesRepository.findCourseById(courseId);
        if (courseById == null) {
            throw new NotFountException("课程不存在");
        }
        if (!Objects.equals(courseById.getUserId(), BaseContext.getCurrentId())) {
            throw new ViolationOperationException("当前用户无权操作该课程");
        }
    }

    //检查需要处理的章节是否属于同一课程
    private static Long getCommonCourseId(List<Chapters> chapters) {
        if (chapters.isEmpty()) {
            throw new DataIllegalException("章节列表不能为空");
        }
        Long courseId = chapters.getFirst().getCourseId();
        for (int i = 1; i < chapters.size(); i++) {
            if (!Objects.equals(chapters.get(i).getCourseId(), courseId)) {
                throw new DataIllegalException("操作的章节必须属于同一课程");
            }
        }
        return courseId;
    }
    //将异常信息提取出来，返回对应的系统异常
    private DataIllegalException translateDuplicateKeyException(DuplicateKeyException exception) {
        //尽量找到异常包装链中最底层、最具体的原因
        Throwable cause = exception.getMostSpecificCause();
        // 获取异常的详细信息
        String detail = cause == null ? "" : cause.getMessage();
        if (detail != null && detail.contains("uk_chapters_course_sort_order")) {
            return new DataIllegalException("章节排序位置已被占用，请刷新章节列表后重试");
        }
        if (detail != null && detail.contains("uk_chapters_course_title")) {
            return new DataIllegalException("同一课程中不能存在同名章节");
        }
        return new DataIllegalException("章节数据与现有记录冲突，请刷新后重试");
    }
    //将DTO列表转换为实体列表
    private List<Chapters> dtoToEntity(List<ChaptersDTO> chaptersDTOList) {
        List<Chapters> chaptersList = new ArrayList<>();
        for (ChaptersDTO chaptersDTO : chaptersDTOList) {
            if (chaptersDTO == null) {
                throw new DataIllegalException("章节数据不能为空");
            }
            Chapters chapters = new Chapters();
            chapters.setId(snowflakeIdGenerator.nextId());
            chapters.setTitle(chaptersDTO.getTitle());
            chapters.setSortOrder(chaptersDTO.getSortOrder());
            chapters.setCreatedAt(LocalDateTime.now());
            chapters.setUpdatedAt(LocalDateTime.now());
            chapters.setCourseId(chaptersDTO.getCourseId());
            chaptersList.add(chapters);
            if (chaptersDTO.getSortOrder() == null
                    || chaptersDTO.getSortOrder() < 0
                    || chaptersDTO.getSortOrder() > MYSQL_UNSIGNED_INT_MAX) {
                throw new ExceededDatabaseLimitException("章节排序值超出数据允许范围，请刷新章节列表后重试");
            }
        }
        Long courseId = getCommonCourseId(chaptersList);
        checkUserOwnsCourse(courseId);
        return chaptersList;
    }
    //将DTO列表转换为实体列表
    private List<Chapters> dtoToUpdateEntities(List<ChaptersDTO> chaptersDTOList) {
        List<Chapters> chaptersList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (ChaptersDTO chaptersDTO : chaptersDTOList) {
            if (chaptersDTO == null) {
                throw new DataIllegalException("章节数据不能为空");
            }
            Chapters chapter = new Chapters();
            chapter.setId(chaptersDTO.getId());
            chapter.setCourseId(chaptersDTO.getCourseId());
            chapter.setTitle(chaptersDTO.getTitle());
            chapter.setSortOrder(chaptersDTO.getSortOrder());
            chapter.setUpdatedAt(now);
            chaptersList.add(chapter);
        }
        return chaptersList;
    }
    //将实体列表转换为VO列表
    private static List<ChaptersVO> getChaptersVOList(List<Chapters> chaptersList) {
        List<ChaptersVO> chaptersVOList = new ArrayList<>();
        for (Chapters chapters : chaptersList) {
            ChaptersVO chaptersVO = new ChaptersVO();
            chaptersVO.setId(chapters.getId());
            chaptersVO.setTitle(chapters.getTitle());
            chaptersVO.setSortOrder(chapters.getSortOrder());
            chaptersVOList.add(chaptersVO);
        }
        return chaptersVOList;
    }
}
