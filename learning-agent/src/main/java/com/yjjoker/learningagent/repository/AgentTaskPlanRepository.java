package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

// 计划和步骤统一从这里读写；第一阶段没有更新或删除入口。
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
}
