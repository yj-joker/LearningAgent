package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.KnowledgePointRelationsDTO;
import com.yjjoker.learningagent.exception.DataIllegalException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.projectenum.KnowledgePointRelationTypeEnum;
import com.yjjoker.learningagent.projectenum.KnowledgePointRelationsSource;
import com.yjjoker.learningagent.projectenum.KnowledgePointRelationsStatus;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.KnowledgePointRelationsRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import com.yjjoker.learningagent.vo.KnowledgePointRelationContext;
import com.yjjoker.learningagent.vo.KnowledgePointRelationsVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识点关系服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgePointRelationsServiceImplTest {

    @Mock
    private KnowledgePointRelationsRepository knowledgePointRelationsRepository;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private CoursesRepository coursesRepository;

    @AfterEach
    void clearBaseContext() {
        BaseContext.removeCurrentId();
        BaseContext.removeCurrentRole();
    }

    @Test
    @DisplayName("两个公开课程知识点可以创建待审核的跨课程关系")
    void shouldCreatePendingRelationForPublicPoints() {
        KnowledgePointRelationsServiceImpl service = service();
        KnowledgePointRelationsDTO dto = relationDTO(2002L, 1001L,
                KnowledgePointRelationTypeEnum.CONFUSABLE);
        BaseContext.setCurrentId(99L);
        BaseContext.setCurrentRole(UserRoleEnum.USER);
        when(coursesRepository.findKnowledgePointRelationContext(2002L, 1001L))
                .thenReturn(context(2002L, 20L, 10L, CoursesTypeEnum.PUBLISHED,
                        1001L, 10L, 20L, CoursesTypeEnum.PUBLISHED));
        when(snowflakeIdGenerator.nextId()).thenReturn(9001L);
        when(knowledgePointRelationsRepository.save(any())).thenReturn(1);

        // 调用创建方法，验证两个公开课程知识点的关系进入待审核状态。
        KnowledgePointRelationsVO result = service.createRelations(dto);

        assertEquals(1001L, result.getFromPointId());
        assertEquals(2002L, result.getToPointId());
        assertEquals(KnowledgePointRelationsSource.USER_SUGGESTED, result.getSource());
        assertEquals(KnowledgePointRelationsStatus.PENDING, result.getStatus());
    }

    @Test
    @DisplayName("任一知识点不存在时不能创建关系")
    void shouldRejectWhenEitherPointIsMissing() {
        KnowledgePointRelationsServiceImpl service = service();
        KnowledgePointRelationsDTO dto = relationDTO(1001L, 2002L,
                KnowledgePointRelationTypeEnum.CONFUSABLE);
        BaseContext.setCurrentId(1L);
        BaseContext.setCurrentRole(UserRoleEnum.USER);
        when(coursesRepository.findKnowledgePointRelationContext(1001L, 2002L))
                .thenReturn(null);

        // 调用创建方法，验证单条 SQL 没有返回完整上下文时拒绝保存。
        assertThrows(NotFountException.class, () -> service.createRelations(dto));

        verify(knowledgePointRelationsRepository, never()).save(any());
    }

    @Test
    @DisplayName("公开课程不能依赖非公开课程中的知识点")
    void shouldRejectPrivatePrerequisiteForPublicCourse() {
        KnowledgePointRelationsServiceImpl service = service();
        KnowledgePointRelationsDTO dto = relationDTO(2002L, 1001L,
                KnowledgePointRelationTypeEnum.PREREQUISITE);
        BaseContext.setCurrentId(99L);
        BaseContext.setCurrentRole(UserRoleEnum.USER);
        when(coursesRepository.findKnowledgePointRelationContext(2002L, 1001L))
                .thenReturn(context(2002L, 20L, 99L, CoursesTypeEnum.PRIVATE,
                        1001L, 10L, 10L, CoursesTypeEnum.PUBLISHED));

        // 调用创建方法，验证关系不会造成公开课程暴露私有前置知识点。
        assertThrows(DataIllegalException.class, () -> service.createRelations(dto));

        verify(knowledgePointRelationsRepository, never()).save(any());
    }

    @Test
    @DisplayName("普通用户不能使用其他用户的非公开课程知识点")
    void shouldRejectPrivatePointOwnedByAnotherUser() {
        KnowledgePointRelationsServiceImpl service = service();
        KnowledgePointRelationsDTO dto = relationDTO(2002L, 1001L,
                KnowledgePointRelationTypeEnum.CONFUSABLE);
        BaseContext.setCurrentId(99L);
        BaseContext.setCurrentRole(UserRoleEnum.USER);
        when(coursesRepository.findKnowledgePointRelationContext(2002L, 1001L))
                .thenReturn(context(2002L, 20L, 88L, CoursesTypeEnum.PRIVATE,
                        1001L, 10L, 10L, CoursesTypeEnum.PRIVATE));

        // 调用创建方法，验证普通用户不能引用其他用户的私有知识点。
        assertThrows(ViolationOperationException.class, () -> service.createRelations(dto));

        verify(knowledgePointRelationsRepository, never()).save(any());
    }

    @Test
    @DisplayName("同一用户拥有的两个非公开知识点可以创建已激活关系")
    void shouldCreateActiveRelationForSameOwnerPrivatePoints() {
        KnowledgePointRelationsServiceImpl service = service();
        KnowledgePointRelationsDTO dto = relationDTO(2002L, 1001L,
                KnowledgePointRelationTypeEnum.CONFUSABLE);
        BaseContext.setCurrentId(99L);
        BaseContext.setCurrentRole(UserRoleEnum.USER);
        when(coursesRepository.findKnowledgePointRelationContext(2002L, 1001L))
                .thenReturn(context(2002L, 20L, 99L, CoursesTypeEnum.PRIVATE,
                        1001L, 10L, 99L, CoursesTypeEnum.PRIVATE));
        when(snowflakeIdGenerator.nextId()).thenReturn(9002L);
        when(knowledgePointRelationsRepository.save(any())).thenReturn(1);

        // 调用创建方法，验证同一用户的私有关系可以直接激活。
        KnowledgePointRelationsVO result = service.createRelations(dto);

        assertEquals(KnowledgePointRelationsStatus.ACTIVE, result.getStatus());
    }

    // 创建被测服务，并显式传入当前构造器所需的全部依赖。
    private KnowledgePointRelationsServiceImpl service() {
        return new KnowledgePointRelationsServiceImpl(
                knowledgePointRelationsRepository,
                snowflakeIdGenerator,
                coursesRepository);
    }

    // 创建关系请求，明确指定关系方向和类型。
    private KnowledgePointRelationsDTO relationDTO(
            Long fromPointId,
            Long toPointId,
            KnowledgePointRelationTypeEnum relationType) {
        KnowledgePointRelationsDTO dto = new KnowledgePointRelationsDTO();
        dto.setFromPointId(fromPointId);
        dto.setToPointId(toPointId);
        dto.setRelationType(relationType);
        return dto;
    }

    // 创建关系两端的课程上下文，模拟单条 JOIN SQL 的返回结果。
    private KnowledgePointRelationContext context(
            Long fromPointId,
            Long fromCourseId,
            Long fromCourseOwnerId,
            CoursesTypeEnum fromCourseType,
            Long toPointId,
            Long toCourseId,
            Long toCourseOwnerId,
            CoursesTypeEnum toCourseType) {
        KnowledgePointRelationContext context = new KnowledgePointRelationContext();
        context.setFromPointId(fromPointId);
        context.setFromCourseId(fromCourseId);
        context.setFromCourseOwnerId(fromCourseOwnerId);
        context.setFromCourseType(fromCourseType);
        context.setToPointId(toPointId);
        context.setToCourseId(toCourseId);
        context.setToCourseOwnerId(toCourseOwnerId);
        context.setToCourseType(toCourseType);
        return context;
    }
}
