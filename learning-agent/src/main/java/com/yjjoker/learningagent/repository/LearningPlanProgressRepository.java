package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.LearningPlanStepProgress;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

// 长期学习进度数据访问接口；所有读取都带用户和计划范围。
@Mapper
public interface LearningPlanProgressRepository {

    // 查询当前计划已有的步骤进度；没有记录的步骤由 Service 补成 NOT_STARTED。
    @Select("""
            SELECT draft_ref AS draftRef, step_ref AS stepRef, user_id AS userId,
                   status, evidence_type AS evidenceType, evidence_summary AS evidenceSummary,
                   assessment_reason AS assessmentReason,
                   evaluated_plan_version AS evaluatedPlanVersion,
                   evaluated_semantic_version AS evaluatedSemanticVersion,
                   version, created_at AS createdAt, updated_at AS updatedAt
            FROM learning_plan_step_progress
            WHERE user_id = #{userId} AND draft_ref = #{draftRef}
            ORDER BY step_ref
            """)
    List<LearningPlanStepProgress> findByDraft(@Param("userId") Long userId,
                                               @Param("draftRef") String draftRef);

    // 只为没有进度记录的新步骤创建初始行，不覆盖已有状态和证据。
    @Insert("""
            INSERT INTO learning_plan_step_progress
                (draft_ref, step_ref, user_id, status, evaluated_plan_version,
                 evaluated_semantic_version, version, created_at, updated_at)
            VALUES (#{progress.draftRef}, #{progress.stepRef}, #{progress.userId},
                    #{progress.status}, #{progress.evaluatedPlanVersion},
                    #{progress.evaluatedSemanticVersion}, #{progress.version},
                    #{progress.createdAt}, #{progress.updatedAt})
            ON DUPLICATE KEY UPDATE step_ref = step_ref
            """)
    int insertIfAbsent(@Param("progress") LearningPlanStepProgress progress);
}
