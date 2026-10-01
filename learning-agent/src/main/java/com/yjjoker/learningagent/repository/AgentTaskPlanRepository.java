package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 计划和步骤统一从这里读写；更新先取得计划版本，不提供删除历史步骤的入口。
@Mapper
public interface AgentTaskPlanRepository {

    // 直接新增，由 run_id 主键阻止重复创建；不能用 upsert 覆盖旧计划。
    @Insert("""
            INSERT INTO agent_task_plans
                (run_id, user_id, session_id, goal, constraints_text, version, created_at, updated_at)
            VALUES (#{plan.runId}, #{plan.userId}, #{plan.sessionId}, #{plan.goal},
                #{plan.constraints}, #{plan.version}, #{plan.createdAt}, #{plan.updatedAt})
            """)
    int insertPlan(@Param("plan") AgentTaskPlan plan);

    // 每步写入都加入服务的同一个事务，中途失败时前面已经写入的步骤也会回滚。
    @Insert("""
            INSERT INTO agent_task_steps
                (step_id, run_id, position, description, completion_criteria, status,
                 result_summary, created_at, updated_at)
            VALUES (#{step.stepId}, #{step.runId}, #{step.position}, #{step.description},
                #{step.completionCriteria}, #{step.status}, #{step.resultSummary},
                #{step.createdAt}, #{step.updatedAt})
            """)
    int insertStep(@Param("step") AgentTaskStep step);

    // 同时匹配用户、会话和任务，不提供只凭 runId 读取计划的方法。
    @Select("""
            SELECT run_id AS runId, user_id AS userId, session_id AS sessionId,
                   goal, constraints_text AS `constraints`, version,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM agent_task_plans
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND run_id = #{runId}
            """)
    Optional<AgentTaskPlan> findPlan(@Param("userId") Long userId,
                                     @Param("sessionId") Long sessionId,
                                     @Param("runId") String runId);

    // 步骤通过所属计划校验归属，再按顺序读取，避免换个 runId 就查到别人的步骤。
    @Select("""
            SELECT s.step_id AS stepId, s.run_id AS runId, s.position, s.description,
                   s.completion_criteria AS completionCriteria, s.status,
                   s.result_summary AS resultSummary,
                   s.created_at AS createdAt, s.updated_at AS updatedAt
            FROM agent_task_steps s
            JOIN agent_task_plans p ON p.run_id = s.run_id
            WHERE p.user_id = #{userId} AND p.session_id = #{sessionId} AND p.run_id = #{runId}
            ORDER BY s.position, s.step_id
            """)
    List<AgentTaskStep> findSteps(@Param("userId") Long userId,
                                  @Param("sessionId") Long sessionId,
                                  @Param("runId") String runId);

    // 版本匹配才递增，同时锁住该计划行；锁一直保留到整个更新事务结束。
    @Update("""
            UPDATE agent_task_plans SET version = version + 1, updated_at = #{updatedAt}
            WHERE user_id = #{userId} AND session_id = #{sessionId}
              AND run_id = #{runId} AND version = #{expectedVersion}
            """)
    int advanceVersion(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                       @Param("runId") String runId, @Param("expectedVersion") long expectedVersion,
                       @Param("updatedAt") LocalDateTime updatedAt);

    // 写事务使用当前读，不能在取得版本后又读取事务早期的旧快照。
    @Select("""
            SELECT run_id AS runId, user_id AS userId, session_id AS sessionId,
                   goal, constraints_text AS `constraints`, version,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM agent_task_plans
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND run_id = #{runId}
            FOR UPDATE
            """)
    Optional<AgentTaskPlan> findPlanForUpdate(@Param("userId") Long userId,
                                             @Param("sessionId") Long sessionId,
                                             @Param("runId") String runId);

    // 按固定顺序锁定本任务的步骤，校验与保存期间不让其他写入改变它们。
    @Select("""
            SELECT s.step_id AS stepId, s.run_id AS runId, s.position, s.description,
                   s.completion_criteria AS completionCriteria, s.status,
                   s.result_summary AS resultSummary,
                   s.created_at AS createdAt, s.updated_at AS updatedAt
            FROM agent_task_steps s
            JOIN agent_task_plans p ON p.run_id = s.run_id
            WHERE p.user_id = #{userId} AND p.session_id = #{sessionId} AND p.run_id = #{runId}
            ORDER BY s.position, s.step_id FOR UPDATE
            """)
    List<AgentTaskStep> findStepsForUpdate(@Param("userId") Long userId,
                                          @Param("sessionId") Long sessionId,
                                          @Param("runId") String runId);

    // 先把旧顺序移出 1～20，再写新顺序，避免交换两步时撞到唯一索引。
    @Update("""
            UPDATE agent_task_steps s JOIN agent_task_plans p ON p.run_id = s.run_id
            SET s.position = s.position + #{offset}
            WHERE p.user_id = #{userId} AND p.session_id = #{sessionId} AND p.run_id = #{runId}
            """)
    int parkStepPositions(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                          @Param("runId") String runId, @Param("offset") int offset);

    // 只更新本任务中的原步骤，不替换固定编号、所属任务或创建时间。
    @Update("""
            UPDATE agent_task_steps s JOIN agent_task_plans p ON p.run_id = s.run_id
            SET s.position = #{step.position}, s.description = #{step.description},
                s.completion_criteria = #{step.completionCriteria}, s.status = #{step.status},
                s.result_summary = #{step.resultSummary}, s.updated_at = #{step.updatedAt}
            WHERE p.user_id = #{userId} AND p.session_id = #{sessionId}
              AND p.run_id = #{step.runId} AND s.step_id = #{step.stepId}
            """)
    int updateStep(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                   @Param("step") AgentTaskStep step);
}
