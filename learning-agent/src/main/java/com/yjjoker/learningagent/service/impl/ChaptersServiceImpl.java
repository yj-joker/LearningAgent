package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.ChaptersDTO;
import com.yjjoker.learningagent.entity.Chapters;
import com.yjjoker.learningagent.exception.*;
import com.yjjoker.learningagent.repository.ChaptersRepository;
import com.yjjoker.learningagent.repository.KnowledgePointsRepository;
import com.yjjoker.learningagent.service.ChaptersService;
import com.yjjoker.learningagent.service.CoursesService;
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
    private final CoursesService coursesService;
    private final KnowledgePointsRepository knowledgePointsRepository;
    //批量添加章节
    @Override
    @Transactional
    public List<ChaptersVO> createChapters(@NonNull List<ChaptersDTO> chaptersDTOList) {
        if (chaptersDTOList.isEmpty()) {
            return List.of();
        }
        // 创建前先校验请求格式和课程一致性，避免无效请求提前消耗雪花 ID。
        Long courseId = validateCreateChapters(chaptersDTOList);
        // 创建章节属于写操作，必须确认当前用户拥有数据库中的目标课程。
        coursesService.checkUserOwnsCourse(courseId);
        // 请求校验通过后，再生成章节 ID 并构造待保存实体。
        List<Chapters> chaptersList = createDTOsToEntities(chaptersDTOList);
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
        // 查询章节属于读取场景：拥有者和已发布课程的访问者都可以查看。
        coursesService.checkUserCanViewCourse(courseId);
        List<Chapters> chaptersList;
        try {
            chaptersList = chaptersRepository.getChaptersByCourseId(courseId);
        } catch (Exception e) {
            throw new LearningAgentServiceException("获取章节列表失败，请稍后再试");
        }
        return getChaptersVOList(chaptersList);
    }

    //根据章节ID批量获取章节；不存在的ID按查询语义忽略，查到的章节必须全部可见
    @Override
    public List<ChaptersVO> getChaptersByIds(@NonNull List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // 查询接口允许不存在的 ID 返回空或部分结果，但请求 ID 本身必须合法。
        validateChapterIds(ids);
        List<Chapters> chapters = findChaptersByIds(ids);
        if (chapters.isEmpty()) {
            return List.of();
        }
        // 使用数据库返回的真实 courseId，一次性校验所有查到章节的读取权限。
        Set<Long> courseIds = new HashSet<>();
        for (Chapters chapter : chapters) {
            courseIds.add(chapter.getCourseId());
        }
        coursesService.checkUserCanViewCourses(courseIds);
        return getChaptersVOList(chapters);
    }

    //根据章节ID删除章节
    @Override
    @Transactional
    public void deleteChaptersByIds(@NonNull List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        // 删除属于严格场景：所有章节必须存在，并且必须属于当前用户拥有的课程。
        validateChapterIds(ids);
        // 确认当前用户拥有所有要删除的章节。
        requireOwnedChapters(ids);
        //删除章节下的知识点
        try {
            knowledgePointsRepository.deleteByChapterId(ids);
        } catch (Exception e) {
            log.error("删除章节下的知识点失败", e);
            throw new LearningAgentServiceException("删除章节失败，请稍后再试");
        }
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

        log.info("删除章节成功");
    }

    //修改章节，允许修改对应的章节的顺序和章节标题
    @Transactional
    @Override
    public List<ChaptersVO> updateChapters(@NonNull List<ChaptersDTO> chaptersDTOList) {
        if (chaptersDTOList.isEmpty()) {
            return List.of();
        }
        // 先校验请求 ID，再读取数据库真实章节并验证所有权。
        List<Chapters> chaptersList = dtoToUpdateEntities(chaptersDTOList);
        Set<Long> chapterIds = validateAndGetUpdateChapterIds(chaptersList);
        List<Chapters> existingChapters = requireOwnedChapters(new ArrayList<>(chapterIds));
        // DTO 中的 courseId 只能作为声明，必须和数据库中的真实归属逐项匹配。
        validateChaptersMatchRequest(existingChapters, chaptersList);
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

    /**
     * 校验更新实体的章节 ID、课程 ID 和排序值，并返回去重后的章节 ID 集合。
     */
    private Set<Long> validateAndGetUpdateChapterIds(List<Chapters> chaptersList) {
        Set<Long> chapterIds = new HashSet<>();
        // 规范数据格式
        for (Chapters chapter : chaptersList) {
            if (chapter.getId() == null || chapter.getCourseId() == null) {
                throw new DataIllegalException("章节 ID 和课程 ID 不能为空");
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

    /**
     * 严格查询章节，并根据数据库中的真实 courseId 校验当前用户的课程所有权。
     * 任意章节不存在，或这些章节属于不同课程，整个写操作都会失败。
     */
    private List<Chapters> requireOwnedChapters(List<Long> ids) {
        List<Chapters> chapters = findChaptersByIds(ids);
        if (chapters.isEmpty() || ids.size() != chapters.size()) {
            throw new NotFountException("部分章节不存在");
        }
        // 使用数据库真实归属检查本次写操作是否只涉及一个课程。
        Long courseId = getCommonCourseId(chapters);
        log.info("操作的章节属于同一课程");
        // 写操作要求课程所有权，已发布但不属于当前用户的课程仍不能修改。
        coursesService.checkUserOwnsCourse(courseId);
        return chapters;
    }

    /**
     * 根据数据库或请求中的 courseId，确认一批章节只属于一个课程。
     */
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

    /**
     * 查询章节实体。该方法只负责数据库访问和异常转换，不负责权限判断。
     * 普通查询可以接受空集合，严格写操作由调用方继续检查返回数量。
     */
    private List<Chapters> findChaptersByIds(List<Long> ids) {
        try {
            return chaptersRepository.getChaptersByIds(ids);
        } catch (Exception e) {
            log.error("根据章节 ID 批量查询章节失败", e);
            throw new LearningAgentServiceException("获取章节列表失败，请稍后再试");
        }
    }

    /**
     * 将数据库中的真实章节与更新请求逐项比较，防止客户端伪造 courseId。
     */
    private void validateChaptersMatchRequest(
            List<Chapters> existingChapters,
            List<Chapters> requestChapters) {
        Map<Long, Long> actualCourseIds = new HashMap<>();
        for (Chapters chapter : existingChapters) {
            actualCourseIds.put(chapter.getId(), chapter.getCourseId());
        }
        for (Chapters chapter : requestChapters) {
            if (!Objects.equals(actualCourseIds.get(chapter.getId()), chapter.getCourseId())) {
                throw new DataIllegalException("章节 ID 与课程 ID 不匹配");
            }
        }
    }

    /**
     * 校验章节 ID 不为空且不重复，供查询、更新和删除入口复用。
     */
    private static void validateChapterIds(List<Long> ids) {
        Set<Long> uniqueIds = new HashSet<>();
        for (Long id : ids) {
            if (id == null || !uniqueIds.add(id)) {
                throw new DataIllegalException("章节 ID 不能为空且不能重复");
            }
        }
    }

    /**
     * 校验批量创建请求中的空元素、排序值和课程一致性，并返回共同的 courseId。
     */
    private Long validateCreateChapters(List<ChaptersDTO> chaptersDTOList) {
        Long courseId = null;
        for (ChaptersDTO dto : chaptersDTOList) {
            if (dto == null) {
                throw new DataIllegalException("章节数据不能为空");
            }
            if (dto.getCourseId() == null) {
                throw new DataIllegalException("课程 ID 不能为空");
            }
            if (dto.getSortOrder() == null
                    || dto.getSortOrder() < 0
                    || dto.getSortOrder() > MYSQL_UNSIGNED_INT_MAX) {
                throw new ExceededDatabaseLimitException("章节排序值超出数据允许范围，请刷新章节列表后重试");
            }
            if (courseId == null) {
                courseId = dto.getCourseId();
            } else if (!Objects.equals(dto.getCourseId(), courseId)) {
                throw new DataIllegalException("批量创建的章节必须属于同一课程");
            }
        }
        return courseId;
    }

    /**
     * 将数据库唯一索引冲突翻译成用户可以理解的章节业务异常。
     */
    private DataIllegalException translateDuplicateKeyException(DuplicateKeyException exception) {
        //尽量找到异常包装链中最底层、最具体的原因
        Throwable cause = exception.getMostSpecificCause();
        // 获取异常的详细信息
        String detail = cause == null ? "" : cause.getMessage();
        if (detail != null && detail.contains("uk_chapters_course_sort_order")) {
            log.error("修改章节时出现排序值相同异常");
            return new DataIllegalException("移动章节错误，请刷新章节列表后重试");
        }
        if (detail != null && detail.contains("uk_chapters_course_title")) {
            return new DataIllegalException("同一课程中不能存在同名章节");
        }
        return new DataIllegalException("章节数据与现有记录冲突，请刷新后重试");
    }

    /**
     * 将创建 DTO 转换为实体，由后端生成章节 ID 和创建时间。
     */
    private List<Chapters> createDTOsToEntities(List<ChaptersDTO> chaptersDTOList) {
        List<Chapters> chaptersList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (ChaptersDTO chaptersDTO : chaptersDTOList) {
            Chapters chapters = mapCommonFields(chaptersDTO);
            chapters.setId(snowflakeIdGenerator.nextId());
            chapters.setCreatedAt(now);
            chapters.setUpdatedAt(now);
            chaptersList.add(chapters);
        }
        return chaptersList;
    }

    /**
     * 将更新 DTO 转换为实体，保留 DTO 中的已有章节 ID。
     */
    private List<Chapters> dtoToUpdateEntities(List<ChaptersDTO> chaptersDTOList) {
        List<Chapters> chaptersList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (ChaptersDTO chaptersDTO : chaptersDTOList) {
            if (chaptersDTO == null) {
                throw new DataIllegalException("章节数据不能为空");
            }
            Chapters chapter = mapCommonFields(chaptersDTO);
            chapter.setId(chaptersDTO.getId());
            chapter.setUpdatedAt(now);
            chaptersList.add(chapter);
        }
        return chaptersList;
    }

    /**
     * 映射创建和更新共同使用的章节字段，避免两条转换路径重复维护 Setter。
     */
    private Chapters mapCommonFields(ChaptersDTO dto) {
        Chapters chapter = new Chapters();
        chapter.setCourseId(dto.getCourseId());
        chapter.setTitle(dto.getTitle());
        chapter.setSortOrder(dto.getSortOrder());
        return chapter;
    }

    /**
     * 将章节实体列表转换为对外返回的 VO 列表。
     */
    private static List<ChaptersVO> getChaptersVOList(List<Chapters> chaptersList) {
        List<ChaptersVO> chaptersVOList = new ArrayList<>();
        for (Chapters chapters : chaptersList) {
            ChaptersVO chaptersVO = new ChaptersVO();
            chaptersVO.setId(chapters.getId());
            chaptersVO.setCourseId(chapters.getCourseId());
            chaptersVO.setTitle(chapters.getTitle());
            chaptersVO.setSortOrder(chapters.getSortOrder());
            chaptersVOList.add(chaptersVO);
        }
        return chaptersVOList;
    }
}
