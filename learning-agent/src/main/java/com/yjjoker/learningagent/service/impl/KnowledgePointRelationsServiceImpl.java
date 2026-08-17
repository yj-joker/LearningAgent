package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.KnowledgePointRelationsDTO;
import com.yjjoker.learningagent.entity.KnowledgePointRelations;
import com.yjjoker.learningagent.exception.DataIllegalException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.NullException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.*;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.KnowledgePointRelationsRepository;
import com.yjjoker.learningagent.service.KnowledgePointRelationsService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import com.yjjoker.learningagent.vo.KnowledgePointRelationContext;
import com.yjjoker.learningagent.vo.KnowledgePointRelationsVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@AllArgsConstructor
@Slf4j
public class KnowledgePointRelationsServiceImpl implements KnowledgePointRelationsService {
    private KnowledgePointRelationsRepository knowledgePointRelationsRepository;
    private SnowflakeIdGenerator snowflakeIdGenerator;
    private CoursesRepository coursesRepository;

    // 创建知识点关系；
    @Override
    public KnowledgePointRelationsVO createRelations(KnowledgePointRelationsDTO relationDTO) {
        // 调用统一参数校验，保证管理员直接调用 Service 时也不会写入非法数据。
        validateRelationRequest(relationDTO);
        // 一次查询确认两个知识点存在，并获取两端课程的所有者和公开状态。
        KnowledgePointRelationContext context = validateKnowledgePointsExistAndCourseIsLegal(relationDTO);
        // 根据两端课程状态计算关系初始状态。
        KnowledgePointRelationsStatus status = getStatus(context);
        //检查关系是否合法：防止易混淆关系重复，防止前置关系形成环路
        KnowledgePointRelations knowledgePointRelations = checkRelationsLegal(relationDTO);
        knowledgePointRelations.setSource(KnowledgePointRelationsSource.USER_SUGGESTED);//关系建议
        knowledgePointRelations.setStatus(status);
        //保存关系
        // 调用统一保存方法，区分唯一键、数据约束和系统异常。
        saveRelation(knowledgePointRelations);
        log.info("关系建议成功");
        return knowledgePointRelationsToVO(knowledgePointRelations);
    }

    // 删除知识点关系；存在性、归属和数据库约束异常处理逻辑待实现。
    @Override
    @Transactional
    public void deleteRelationsByIds(List<Long> ids) {
        if (ids == null) {
            throw new DataIllegalException("关系 ID 列表不能为空");
        }
        if (ids.isEmpty()) {
            return;
        }
        // 检查关系 ID 必须有效，并且一次删除请求中不能重复。
        Set<Long> idSet = new HashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new DataIllegalException("关系 ID 必须大于 0");
            }
            if (!idSet.add(id)) {
                throw new DataIllegalException("关系 ID 不能重复");
            }
        }
        if (BaseContext.getCurrentRole() != UserRoleEnum.ADMIN) {
            throw new ViolationOperationException("无权限删除");
        }
        // 删除前确认所有目标关系存在，避免把不存在的 ID 当成服务器错误。
        List<KnowledgePointRelations> existingRelations;
        try {
            existingRelations = knowledgePointRelationsRepository.findByIds(ids);
        } catch (Exception e) {
            log.error("删除前查询知识点关系失败，ids={}", ids, e);
            throw new LearningAgentServiceException("删除关系失败，请稍后再试");
        }
        if (existingRelations.size() != ids.size()) {
            throw new NotFountException("部分知识点关系不存在");
        }
        int result;
        try {
            result = knowledgePointRelationsRepository.deleteAll(ids);
        } catch (DataIntegrityViolationException e) {
            log.warn("删除知识点关系时违反数据完整性约束，ids={}", ids, e);
            throw new DataIllegalException("知识点关系仍被其他数据引用，无法删除");
        } catch (Exception e) {
            log.error("删除知识点关系失败，ids={}", ids, e);
            throw new LearningAgentServiceException("删除关系失败，请稍后再试");
        }
        if (result != ids.size()) {
            throw new LearningAgentServiceException("删除关系失败");
        }
        log.info("删除关系成功");
    }

    //将创建关系的DTO转换为实体
    private KnowledgePointRelations relationDTOToCreateEntity(KnowledgePointRelationsDTO relationDTO) {
        KnowledgePointRelations knowledgePointRelations = DTOToEntity(relationDTO);
        knowledgePointRelations.setId(snowflakeIdGenerator.nextId());
        return knowledgePointRelations;
    }

    //将DTO转换为实体
    private KnowledgePointRelations DTOToEntity(KnowledgePointRelationsDTO relationDTO) {
        KnowledgePointRelations knowledgePointRelations = new KnowledgePointRelations();
        knowledgePointRelations.setFromPointId(relationDTO.getFromPointId());
        knowledgePointRelations.setToPointId(relationDTO.getToPointId());
        knowledgePointRelations.setRelationType(relationDTO.getRelationType());
        return knowledgePointRelations;
    }

    // 校验创建请求的对象、端点 ID 和关系类型，防止绕过 Controller 校验直接调用 Service。
    private void validateRelationRequest(KnowledgePointRelationsDTO relationDTO) {
        if (relationDTO == null) {
            throw new NullException("知识点关系不能为空");
        }
        if (relationDTO.getFromPointId() == null || relationDTO.getFromPointId() <= 0
                || relationDTO.getToPointId() == null || relationDTO.getToPointId() <= 0) {
            throw new DataIllegalException("知识点 ID 必须大于 0");
        }
        if (relationDTO.getRelationType() == null) {
            throw new NullException("关系类型不能为空");
        }
        if (Objects.equals(relationDTO.getFromPointId(), relationDTO.getToPointId())) {
            throw new DataIllegalException("知识点关系的起点和终点不能相同");
        }
    }

    // 一次查询确认关系两端的知识点和课程都存在，并完成私有课程权限与可见性校验。
    private KnowledgePointRelationContext validateKnowledgePointsExistAndCourseIsLegal(
            KnowledgePointRelationsDTO relationDTO) {
        KnowledgePointRelationContext context;
        try {
            context = coursesRepository.findKnowledgePointRelationContext(
                    relationDTO.getFromPointId(), relationDTO.getToPointId());
        } catch (Exception e) {
            log.error("校验关系端点知识点失败，fromPointId={}, toPointId={}",
                    relationDTO.getFromPointId(), relationDTO.getToPointId(), e);
            throw new LearningAgentServiceException("校验知识点失败，请稍后再试");
        }
        if (context == null) {
            throw new NotFountException("部分知识点不存在");
        }
        // 当前用户只能使用公开课程或自己拥有的非公开课程知识点。
        validatePrivateCourseAccess(context);
        // 防止激活关系后，通过公开课程查询暴露非公开知识点。
        validateRelationVisibility(context, relationDTO.getRelationType());
        return context;
    }

    // 校验当前用户是否有权使用关系两端的非公开课程。
    private void validatePrivateCourseAccess(KnowledgePointRelationContext context) {
        if (BaseContext.getCurrentRole() == UserRoleEnum.ADMIN) {
            return;
        }
        Long currentUserId = BaseContext.getCurrentId();
        if (!isPublished(context.getFromCourseType())
                && !Objects.equals(context.getFromCourseOwnerId(), currentUserId)) {
            throw new ViolationOperationException("无权使用前置知识点所属的非公开课程");
        }
        if (!isPublished(context.getToCourseType())
                && !Objects.equals(context.getToCourseOwnerId(), currentUserId)) {
            throw new ViolationOperationException("无权使用目标知识点所属的非公开课程");
        }
    }

    // 校验关系激活后不会通过课程关系查询暴露其他用户的非公开知识点。
    private void validateRelationVisibility(
            KnowledgePointRelationContext context,
            KnowledgePointRelationTypeEnum relationType) {
        boolean fromPublished = isPublished(context.getFromCourseType());
        boolean toPublished = isPublished(context.getToCourseType());

        if (relationType == KnowledgePointRelationTypeEnum.PREREQUISITE
                && toPublished && !fromPublished) {
            throw new DataIllegalException("公开课程不能依赖非公开课程中的知识点");
        }

        if (relationType == KnowledgePointRelationTypeEnum.CONFUSABLE
                && fromPublished != toPublished) {
            throw new DataIllegalException("公开课程不能与非公开课程建立易混淆关系");
        }

        if (!fromPublished && !toPublished
                && !Objects.equals(context.getFromCourseOwnerId(), context.getToCourseOwnerId())) {
            throw new DataIllegalException("不同所有者的非公开课程不能建立知识点关系");
        }
    }

    // 只有 PUBLISHED 课程对其他用户可见，PRIVATE 和 PENDING 都属于非公开课程。
    private boolean isPublished(CoursesTypeEnum courseType) {
        return courseType == CoursesTypeEnum.PUBLISHED;
    }

    // 持久化知识点关系，并把数据库异常转换为客户端可理解的业务异常。
    private void saveRelation(KnowledgePointRelations knowledgePointRelations) {
        int result;
        try {
            result = knowledgePointRelationsRepository.save(knowledgePointRelations);
        } catch (DuplicateKeyException e) {
            log.warn("保存知识点关系时发生唯一键冲突", e);
            throw translateDuplicateKeyException(e);
        } catch (DataIntegrityViolationException e) {
            log.warn("保存知识点关系时违反数据完整性约束", e);
            throw new DataIllegalException("知识点关系不符合数据库约束");
        } catch (Exception e) {
            log.error("保存知识点关系失败", e);
            throw new LearningAgentServiceException("保存关系失败，请稍后再试");
        }
        if (result != 1) {
            throw new LearningAgentServiceException("保存关系失败，请稍后再试");
        }
    }

    // 将关系唯一索引冲突转换为明确的客户端提示。
    private DataIllegalException translateDuplicateKeyException(DuplicateKeyException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String detail = cause == null ? "" : cause.getMessage();
        if (detail != null && detail.contains("uk_kpr_relation")) {
            return new DataIllegalException("相同的知识点关系已经存在");
        }
        return new DataIllegalException("知识点关系与现有数据冲突");
    }

    //将实体转换为VO
    private KnowledgePointRelationsVO knowledgePointRelationsToVO(KnowledgePointRelations knowledgePointRelations) {
        KnowledgePointRelationsVO knowledgePointRelationsVO = new KnowledgePointRelationsVO();
        knowledgePointRelationsVO.setId(knowledgePointRelations.getId());
        knowledgePointRelationsVO.setFromPointId(knowledgePointRelations.getFromPointId());
        knowledgePointRelationsVO.setToPointId(knowledgePointRelations.getToPointId());
        knowledgePointRelationsVO.setRelationType(knowledgePointRelations.getRelationType());
        knowledgePointRelationsVO.setSource(knowledgePointRelations.getSource());
        knowledgePointRelationsVO.setStatus(knowledgePointRelations.getStatus());
        knowledgePointRelationsVO.setCreatedAt(knowledgePointRelations.getCreatedAt());
        return knowledgePointRelationsVO;
    }

    //检查关系是否合法：防止易混淆关系重复，防止前置关系形成环路
    private @NonNull KnowledgePointRelations checkRelationsLegal(KnowledgePointRelationsDTO relationDTO) {
        KnowledgePointRelations knowledgePointRelations = relationDTOToCreateEntity(relationDTO);
        //如果关系为易混淆关系，就约定大id在后，小id在前，防止关系重复
        if (knowledgePointRelations.getRelationType() == KnowledgePointRelationTypeEnum.CONFUSABLE) {
            Long fromPointId = knowledgePointRelations.getFromPointId();
            Long toPointId = knowledgePointRelations.getToPointId();
            knowledgePointRelations.setFromPointId(
                    Math.min(fromPointId, toPointId));
            knowledgePointRelations.setToPointId(
                    Math.max(fromPointId, toPointId));
        } else {
            //如果是前置关系，那么就判断是否形成环
            boolean ring;
            try {
                ring = knowledgePointRelationsRepository.ringDetection(
                        knowledgePointRelations.getFromPointId(), knowledgePointRelations.getToPointId());
            } catch (Exception e) {
                log.error("检查知识点关系环路失败，fromPointId={}, toPointId={}",
                        knowledgePointRelations.getFromPointId(), knowledgePointRelations.getToPointId(), e);
                throw new LearningAgentServiceException("检查关系合法性失败，请稍后再试");
            }
            if (ring) {
                throw new DataIllegalException("添加的关系会导致环路");
            }
        }
        knowledgePointRelations.setCreatedBy(BaseContext.getCurrentId());
        knowledgePointRelations.setCreatedAt(LocalDateTime.now());
        return knowledgePointRelations;
    }
    // 根据两端课程状态获取关系初始状态；两个公开课程的关系需要审核，其余为私有关系。
    private static @NonNull KnowledgePointRelationsStatus getStatus(
            KnowledgePointRelationContext context) {
        if (context.getFromCourseType() == CoursesTypeEnum.PUBLISHED
                && context.getToCourseType() == CoursesTypeEnum.PUBLISHED) {
            return KnowledgePointRelationsStatus.PENDING;
        }
        return KnowledgePointRelationsStatus.ACTIVE;
    }
}
