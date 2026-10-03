package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.entity.LearningPlanDraftStep;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

// 草案数据访问接口；每个查询都带 userId，避免仅凭 draftRef 访问其他用户的内容。
@Mapper
public interface LearningPlanDraftRepository {

    // 保存草案主体；草案版本从 1 开始。
    @Insert("""
            INSERT INTO learning_plan_drafts
                (draft_ref, user_id, title, objective, learner_profile, weekly_commitment,
                 constraints_text, status, source, version, created_at, updated_at)
            VALUES (#{draft.draftRef}, #{draft.userId}, #{draft.title}, #{draft.objective},
                #{draft.learnerProfile}, #{draft.weeklyCommitment}, #{draft.constraints},
                #{draft.status}, #{draft.source}, #{draft.version}, #{draft.createdAt}, #{draft.updatedAt})
            """)
    int insertDraft(@Param("draft") LearningPlanDraft draft);

    // 保存草案中的一个步骤；position 由 Service 按完整列表重新编号。
    @Insert("""
            INSERT INTO learning_plan_draft_steps
                (step_ref, draft_ref, position, description, completion_criteria, created_at, updated_at)
            VALUES (#{step.stepRef}, #{step.draftRef}, #{step.position}, #{step.description},
                #{step.completionCriteria}, #{step.createdAt}, #{step.updatedAt})
            """)
    int insertStep(@Param("step") LearningPlanDraftStep step);

    // 查询当前用户的全部草案主体，步骤由 Service 单独批量加载。
    @Select("""
            SELECT draft_ref AS draftRef, user_id AS userId, title, objective,
                   learner_profile AS learnerProfile, weekly_commitment AS weeklyCommitment,
                   constraints_text AS `constraints`, status, source, version,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM learning_plan_drafts
            WHERE user_id = #{userId} AND status IN ('DRAFT', 'ACTIVE')
            ORDER BY updated_at DESC, draft_ref DESC
            """)
    List<LearningPlanDraft> findDrafts(@Param("userId") Long userId);

    // 按用户和稳定引用查询一份草案主体。
    @Select("""
            SELECT draft_ref AS draftRef, user_id AS userId, title, objective,
                   learner_profile AS learnerProfile, weekly_commitment AS weeklyCommitment,
                   constraints_text AS `constraints`, status, source, version,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM learning_plan_drafts
            WHERE user_id = #{userId} AND draft_ref = #{draftRef} AND status IN ('DRAFT', 'ACTIVE')
            """)
    LearningPlanDraft findDraft(@Param("userId") Long userId, @Param("draftRef") String draftRef);

    // 更新时锁住 DRAFT 或 ACTIVE 主体，保证版本校验和步骤替换在同一个事务中完成。
    @Select("""
            SELECT draft_ref AS draftRef, user_id AS userId, title, objective,
                   learner_profile AS learnerProfile, weekly_commitment AS weeklyCommitment,
                   constraints_text AS `constraints`, status, source, version,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM learning_plan_drafts
            WHERE user_id = #{userId} AND draft_ref = #{draftRef}
              AND status IN ('DRAFT', 'ACTIVE')
            FOR UPDATE
            """)
    LearningPlanDraft findDraftForUpdate(@Param("userId") Long userId, @Param("draftRef") String draftRef);

    // 查询一份草案的步骤，返回给页面和模型时不暴露内部时间字段。
    @Select("""
            SELECT step_ref AS stepRef, draft_ref AS draftRef, position, description,
                   completion_criteria AS completionCriteria,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM learning_plan_draft_steps
            WHERE draft_ref = #{draftRef}
            ORDER BY position, step_ref
            """)
    List<LearningPlanDraftStep> findSteps(@Param("draftRef") String draftRef);

    // 版本匹配才更新主体；影响行数不是 1 就说明草案已被其他请求修改。
    @Update("""
            UPDATE learning_plan_drafts
            SET title = #{draft.title}, objective = #{draft.objective},
                learner_profile = #{draft.learnerProfile}, weekly_commitment = #{draft.weeklyCommitment},
                constraints_text = #{draft.constraints}, source = #{draft.source},
                version = version + 1, updated_at = #{updatedAt}
            WHERE draft_ref = #{draft.draftRef} AND user_id = #{userId}
              AND status IN ('DRAFT', 'ACTIVE') AND version = #{expectedVersion}
            """)
    int updateDraft(@Param("draft") LearningPlanDraft draft,
                    @Param("userId") Long userId,
                    @Param("expectedVersion") long expectedVersion,
                    @Param("updatedAt") LocalDateTime updatedAt);

    // 只有草案状态和版本都匹配时才能正式生效，避免旧页面越过最新修改直接确认。
    @Update("""
            UPDATE learning_plan_drafts
            SET status = 'ACTIVE', version = version + 1, updated_at = #{updatedAt}
            WHERE draft_ref = #{draftRef} AND user_id = #{userId}
              AND status = 'DRAFT' AND version = #{expectedVersion}
            """)
    int activateDraft(@Param("draftRef") String draftRef,
                      @Param("userId") Long userId,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("updatedAt") LocalDateTime updatedAt);

    // 更新采用完整步骤列表，先删除旧步骤再插入新列表，避免顺序交换撞到唯一索引。
    @Delete("DELETE FROM learning_plan_draft_steps WHERE draft_ref = #{draftRef}")
    int deleteSteps(@Param("draftRef") String draftRef);
}
