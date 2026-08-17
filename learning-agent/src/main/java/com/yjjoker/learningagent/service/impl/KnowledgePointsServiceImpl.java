package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.KnowledgePointsDTO;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.entity.KnowledgePoints;
import com.yjjoker.learningagent.exception.DataIllegalException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.KnowledgePointsRepository;
import com.yjjoker.learningagent.service.ChaptersService;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.service.KnowledgePointsService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import com.yjjoker.learningagent.vo.ChaptersVO;
import com.yjjoker.learningagent.vo.KnowledgePointsVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@AllArgsConstructor
@Slf4j
public class KnowledgePointsServiceImpl implements KnowledgePointsService {
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ChaptersService chaptersService;
    private final KnowledgePointsRepository knowledgePointsRepository;
    private final CoursesService coursesService;
    private final CoursesRepository coursesRepository;

    //创建知识点
    @Override
    @Transactional
    public List<KnowledgePointsVO> createKnowledgePoint(List<KnowledgePointsDTO> knowledgePointsDTO) {
        if (knowledgePointsDTO == null || knowledgePointsDTO.isEmpty()) {
            return List.of();
        }
        // 验证前端传入的知识点所属课程一致
        validateKnowledgePointsSameCourse(knowledgePointsDTO);
        //验证前端传入的章节属于对应的课程
        validateChaptersBelongToCourses(knowledgePointsDTO);
        //验证用户是否有权限处理这些知识点
        userCanHandle(knowledgePointsDTO);
        //将知识点DTO列表转换为实体列表
        List<KnowledgePoints> knowledgePoints = createDTOsToEntities(knowledgePointsDTO);
        int result;
        try {
            result = knowledgePointsRepository.saveAll(knowledgePoints);
        } catch (DuplicateKeyException e) {
            log.warn("创建知识点时发生唯一键冲突", e);
            throw translateDuplicateKeyException(e);
        } catch (DataIntegrityViolationException e) {
            log.warn("创建知识点时违反数据完整性约束", e);
            throw new DataIllegalException("知识点数据不符合数据库约束");
        } catch (Exception e) {
            log.error("保存知识点mysql出错", e);
            throw new LearningAgentServiceException("保存知识点时出错，请稍后再试");
        }
        if (result != knowledgePoints.size()) {
            log.error("保存知识点mysql出错，保存数量不一致");
            throw new LearningAgentServiceException("保存知识点时出错，请稍后再试");
        }
        List<KnowledgePointsVO> knowledgePointsVOS = knowledgePointsEntityToVO(knowledgePoints);
        log.info("保存知识点到mysql成功");
        //TODO 异步处理source，进行重排序
        return knowledgePointsVOS;
    }

    //根据ids获取知识点
    @Override
    public List<KnowledgePointsVO> getKnowledgePointByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        //检验前端传递的知识点id不为null和不重复
        validateKnowledgePointIds(ids);
        List<KnowledgePoints> knowledgePoints = findKnowledgePointsByIds(ids);
        if (knowledgePoints.isEmpty()) {
            return List.of();
        }
        //校验用户有权限查看这些知识点所属的课程
        coursesService.checkUserCanViewCourses(getCourseIds(knowledgePoints));
        log.info("根据id获取知识点成功{}", knowledgePoints);
        return knowledgePointsEntityToVO(knowledgePoints);
    }

    //根据章节id获取知识点列表
    @Override
    public List<KnowledgePointsVO> getKnowledgePointsByChapterId(Long chapterId) {
        if (chapterId == null || chapterId <= 0) {
            throw new DataIllegalException("章节 ID 必须大于 0");
        }
        // 章节不存在时按列表查询语义返回空；存在时由章节服务校验读取权限。
        if (chaptersService.getChaptersByIds(List.of(chapterId)).isEmpty()) {
            return List.of();
        }
        List<KnowledgePoints> knowledgePoints;
        try {
            knowledgePoints = knowledgePointsRepository.findByChapterId(chapterId);
        } catch (Exception e) {
            log.error("根据章节id获取知识点时出错", e);
            throw new LearningAgentServiceException("获取知识点时出错，请稍后再试");
        }
        log.info("根据章节id获取知识点成功{}", knowledgePoints);
        return knowledgePointsEntityToVO(knowledgePoints);
    }

    //更新知识点
    @Override
    @Transactional
    public List<KnowledgePointsVO> updateKnowledgePoint(List<KnowledgePointsDTO> knowledgePointsDTO) {
        if (knowledgePointsDTO == null || knowledgePointsDTO.isEmpty()) {
            return List.of();
        }
        //验证前端传递的更新知识点id是否不为空且不重复
        validateUpdateIds(knowledgePointsDTO);
        //验证前端传递的知识点所属课程一致
        validateKnowledgePointsSameCourse(knowledgePointsDTO);
        List<Long> knowledgePointIds = knowledgePointsDTO.stream()
                .map(KnowledgePointsDTO::getId)
                .toList();
        //判断前端传递的知识点id是否全部有效，传递的课程id存在并且属于当前用户
        List<KnowledgePoints> existingKnowledgePoints = requireOwnedKnowledgePoints(knowledgePointIds);
        //验证前端传递的知识点所属的课程id和章节id和数据库当中的知识点的所属课程id和章节id一致
        validateKnowledgePointsMatchRequest(existingKnowledgePoints, knowledgePointsDTO);
        //验证前端传递的章节属于对应的课程
        validateChaptersBelongToCourses(knowledgePointsDTO);
        List<KnowledgePoints> knowledgePoints = updateDTOsToEntities(knowledgePointsDTO);
        int result;
        try {
            result = knowledgePointsRepository.updateAll(knowledgePoints);
        } catch (DuplicateKeyException e) {
            log.warn("更新知识点时发生唯一键冲突", e);
            throw translateDuplicateKeyException(e);
        } catch (DataIntegrityViolationException e) {
            log.warn("更新知识点时违反数据完整性约束", e);
            throw new DataIllegalException("知识点数据不符合数据库约束");
        } catch (Exception e) {
            log.error("更新知识点时发生数据库异常", e);
            throw new LearningAgentServiceException("更新知识点时出错，请稍后再试");
        }
        if (result != knowledgePoints.size()) {
            log.error("更新知识点mysql出错，更新数量不一致");
            throw new LearningAgentServiceException("更新知识点时出错，请稍后再试");
        }
        log.info("更新知识点成功");
        return knowledgePointsEntityToVO(knowledgePoints);
    }

    //删除知识点
    @Override
    @Transactional
    public void deleteKnowledgePoint(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        //验证前端传递的知识点id是否不为空且不重复
        validateKnowledgePointIds(ids);
        //判断前端传递的知识点id是否全部有效，传递的课程id存在并且属于当前用户
        requireOwnedKnowledgePoints(ids);
        int result;
        try {
            result = knowledgePointsRepository.deleteAll(ids);
        } catch (DataIntegrityViolationException e) {
            log.warn("删除知识点时违反数据完整性约束", e);
            throw new DataIllegalException("知识点仍被其他数据引用，无法删除");
        } catch (Exception e) {
            log.error("删除知识点时出错", e);
            throw new LearningAgentServiceException("删除知识点时出错，请稍后再试");
        }
        if (result != ids.size()) {
            log.error("删除知识点mysql出错，删除数量不一致");
            throw new LearningAgentServiceException("删除知识点时出错，请稍后再试");
        }
        log.info("删除知识点成功");
    }

    //根据课程id获取易混淆知识点
    @Override
    public List<KnowledgePointsVO> getConfusableKnowledgePointsByCourseId(Long courseId) {
        check(courseId);
        List<KnowledgePoints> knowledgePoints;
        try {
            knowledgePoints = knowledgePointsRepository.getConfusableKnowledgePointsByCourseId(courseId);
        } catch (Exception e) {
            log.error("查询课程易混淆知识点失败，courseId={}", courseId, e);
            throw new LearningAgentServiceException("查询知识点失败");
        }
        List<KnowledgePointsVO> list = knowledgePointsEntityToVO(knowledgePoints);
        log.info("查询易混淆知识点成功");
        return list;
    }

    //根据课程id获取前置知识点
    @Override
    public List<KnowledgePointsVO> getPrerequisiteKnowledgePointsByCourseId(Long courseId) {
        check(courseId);
        List<KnowledgePoints> knowledgePoints;
        try {
            knowledgePoints = knowledgePointsRepository.getPrerequisiteKnowledgePointsByCourseId(courseId);
        } catch (Exception e) {
            log.error("查询课程前置知识点失败，courseId={}", courseId, e);
            throw new LearningAgentServiceException("查询知识点失败");
        }
        List<KnowledgePointsVO> list = knowledgePointsEntityToVO(knowledgePoints);
        log.info("查询前置知识点成功");
        return list;
    }

    // 将创建 DTO 转换为实体，由后端生成主键和创建时间。
    private List<KnowledgePoints> createDTOsToEntities(List<KnowledgePointsDTO> knowledgePointsDTO) {
        List<KnowledgePoints> knowledgePoints = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgePointsDTO pointsDTO : knowledgePointsDTO) {
            KnowledgePoints knowledgePoint = mapCommonFields(pointsDTO);
            knowledgePoint.setId(snowflakeIdGenerator.nextId());
            knowledgePoint.setCreatedBy(BaseContext.getCurrentId());
            knowledgePoint.setCreatedAt(now);
            knowledgePoints.add(knowledgePoint);
        }
        return knowledgePoints;
    }

    // 将更新 DTO 转换为实体
    private List<KnowledgePoints> updateDTOsToEntities(List<KnowledgePointsDTO> knowledgePointsDTO) {
        List<KnowledgePoints> knowledgePoints = new ArrayList<>();
        for (KnowledgePointsDTO pointsDTO : knowledgePointsDTO) {
            KnowledgePoints knowledgePoint = mapCommonFields(pointsDTO);
            knowledgePoints.add(knowledgePoint);
        }
        return knowledgePoints;
    }

    //将知识点实体列表转换为VO列表
    private List<KnowledgePointsVO> knowledgePointsEntityToVO(List<KnowledgePoints> knowledgePoints) {
        List<KnowledgePointsVO> knowledgePointsVOS = new ArrayList<>();
        for (KnowledgePoints knowledgePoint : knowledgePoints) {
            KnowledgePointsVO knowledgePointsVO = new KnowledgePointsVO();
            knowledgePointsVO.setId(knowledgePoint.getId());
            knowledgePointsVO.setName(knowledgePoint.getName());
            knowledgePointsVO.setSortOrder(knowledgePoint.getSortOrder());
            knowledgePointsVO.setDescription(knowledgePoint.getDescription());
            knowledgePointsVO.setCreatedBy(knowledgePoint.getCreatedBy());
            knowledgePointsVO.setCreatedAt(knowledgePoint.getCreatedAt());
            knowledgePointsVO.setUpdatedAt(knowledgePoint.getUpdatedAt());
            knowledgePointsVOS.add(knowledgePointsVO);
        }
        log.info("将知识点实体列表转换为VO列表成功");
        return knowledgePointsVOS;
    }

    //捕获对应的异常
    private DataIllegalException translateDuplicateKeyException(DuplicateKeyException exception) {
        //尽量找到异常包装链中最底层、最具体的原因
        Throwable cause = exception.getMostSpecificCause();
        // 获取异常的详细信息
        String detail = cause == null ? "" : cause.getMessage();
        if (detail != null && detail.contains("uk_knowledge_points_course_sort_order")) {
            return new DataIllegalException("知识点排序位置已被占用，请刷新知识点列表后重试");
        }
        if (detail != null && detail.contains("uk_knowledge_points_chapter_name")) {
            return new DataIllegalException("同一章节中不能存在同名知识点");
        }
        return new DataIllegalException("知识点数据与现有记录冲突，请刷新后重试");
    }

    // 判断当前用户是否可以操作 DTO 中声明的课程。
    private void userCanHandle(List<KnowledgePointsDTO> knowledgePointsDTO) {
        //判断当前用户是否是课程所有者
        coursesService.checkUserOwnsCourse(knowledgePointsDTO.getFirst().getCourseId());
    }

    // 判断请求中的所有知识点是否声明为同一课程。
    private void validateKnowledgePointsSameCourse(List<KnowledgePointsDTO> knowledgePointsDTO) {
        if (knowledgePointsDTO.getFirst() == null) {
            throw new DataIllegalException("知识点数据不能为空");
        }
        Long firstCourseId = knowledgePointsDTO.getFirst().getCourseId();
        for (KnowledgePointsDTO pointsDTO : knowledgePointsDTO) {
            if (pointsDTO == null) {
                throw new DataIllegalException("知识点数据不能为空");
            }
            if (!Objects.equals(pointsDTO.getCourseId(), firstCourseId)) {
                throw new DataIllegalException("所有知识点必须属于同一个课程");
            }
        }
    }

    // 更新时知识点 ID 必须存在，且一次请求中不能重复。
    private void validateUpdateIds(List<KnowledgePointsDTO> knowledgePointsDTO) {
        Set<Long> knowledgePointIds = new HashSet<>();
        for (KnowledgePointsDTO pointsDTO : knowledgePointsDTO) {
            if (pointsDTO == null || pointsDTO.getId() == null) {
                throw new DataIllegalException("更新知识点时 ID 不能为空");
            }
            if (!knowledgePointIds.add(pointsDTO.getId())) {
                throw new DataIllegalException("同一个知识点不能在一次请求中重复更新");
            }
        }
    }

    // 将 DTO 中的公共字段映射到实体
    private KnowledgePoints mapCommonFields(KnowledgePointsDTO dto) {
        KnowledgePoints entity = new KnowledgePoints();
        entity.setId(dto.getId());
        entity.setCourseId(dto.getCourseId());
        entity.setChapterId(dto.getChapterId());
        entity.setName(dto.getName());
        entity.setSortOrder(dto.getSortOrder());
        entity.setDescription(dto.getDescription());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    // 验证知识点 ID 列表不能为空且不能重复
    private void validateKnowledgePointIds(List<Long> ids) {
        Set<Long> uniqueIds = new HashSet<>();
        for (Long id : ids) {
            if (id == null || !uniqueIds.add(id)) {
                throw new DataIllegalException("知识点 ID 不能为空且不能重复");
            }
        }
    }

    // 根据 ID 列表查询知识点
    private List<KnowledgePoints> findKnowledgePointsByIds(List<Long> ids) {
        try {
            return knowledgePointsRepository.findByIds(ids);
        } catch (Exception e) {
            log.error("根据知识点 ID 批量查询知识点失败", e);
            throw new LearningAgentServiceException("获取知识点时出错，请稍后再试");
        }
    }

    //判断前端传递的知识点id是否全部有效，传递的课程id存在并且属于当前用户
    private List<KnowledgePoints> requireOwnedKnowledgePoints(List<Long> ids) {
        List<KnowledgePoints> knowledgePoints = findKnowledgePointsByIds(ids);
        if (knowledgePoints.size() != ids.size()) {
            throw new NotFountException("部分知识点不存在");
        }
        coursesService.checkUserOwnsCourses(getCourseIds(knowledgePoints));
        return knowledgePoints;
    }

    private Set<Long> getCourseIds(List<KnowledgePoints> knowledgePoints) {
        Set<Long> courseIds = new HashSet<>();
        for (KnowledgePoints knowledgePoint : knowledgePoints) {
            courseIds.add(knowledgePoint.getCourseId());
        }
        return courseIds;
    }

    // 创建和更新时，使用数据库中的真实章节归属校验 DTO 的 chapterId/courseId。
    private void validateChaptersBelongToCourses(List<KnowledgePointsDTO> knowledgePointsDTO) {
        Set<Long> chapterIds = new HashSet<>();
        for (KnowledgePointsDTO dto : knowledgePointsDTO) {
            chapterIds.add(dto.getChapterId());
        }
        // 根据 ID 列表查询章节
        List<ChaptersVO> chapters = chaptersService.getChaptersByIds(new ArrayList<>(chapterIds));
        if (chapters.size() != chapterIds.size()) {
            throw new NotFountException("部分章节不存在");
        }
        // 构建章节 ID 到课程 ID 的映射
        Map<Long, Long> chapterCourseIds = new HashMap<>();
        for (ChaptersVO chapter : chapters) {
            chapterCourseIds.put(chapter.getId(), chapter.getCourseId());
        }
        // 验证章节 ID 与课程 ID 的匹配
        for (KnowledgePointsDTO dto : knowledgePointsDTO) {
            if (!Objects.equals(chapterCourseIds.get(dto.getChapterId()), dto.getCourseId())) {
                throw new DataIllegalException("章节 ID 与课程 ID 不匹配");
            }
        }
    }

    // 更新时不能相信 DTO 的归属字段，必须与数据库中的原记录逐项比较。比较课程 ID 和章节 ID，确保数据一致性和归属关系的准确性
    private void validateKnowledgePointsMatchRequest(
            List<KnowledgePoints> existingKnowledgePoints,
            List<KnowledgePointsDTO> knowledgePointsDTO) {
        Map<Long, KnowledgePoints> existingById = new HashMap<>();
        for (KnowledgePoints knowledgePoint : existingKnowledgePoints) {
            existingById.put(knowledgePoint.getId(), knowledgePoint);
        }
        for (KnowledgePointsDTO dto : knowledgePointsDTO) {
            KnowledgePoints existing = existingById.get(dto.getId());
            if (!Objects.equals(existing.getCourseId(), dto.getCourseId())) {
                throw new DataIllegalException("知识点 ID 与课程 ID 不匹配");
            }
            if (!Objects.equals(existing.getChapterId(), dto.getChapterId())) {
                throw new DataIllegalException("知识点 ID 与章节 ID 不匹配");
            }
        }
    }

    //检查课程是否存在，用户是否拥有该课程，用户是否可以查看该课程
    private void check(Long courseId) {
        Courses courseById;
        try {
            courseById = coursesRepository.findCourseById(courseId);
        } catch (Exception e) {
            throw new LearningAgentServiceException("检查课程失败，请稍后再试");
        }
        if (courseById == null) {
            throw new NotFountException("课程不存在");
        }
        if (!courseById.getUserId().equals(BaseContext.getCurrentId()) && courseById.getCourseType() != CoursesTypeEnum.PUBLISHED) {
            throw new ViolationOperationException("无权限访问");
        }
    }
}
