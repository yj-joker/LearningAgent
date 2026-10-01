package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.SessionFocusState;
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

    // 直接新增，由 plan_id 主键阻止重复创建；不能用 upsert 覆盖旧计划。
    @Insert("""
            INSERT INTO agent_task_plans
                (plan_id, user_id, session_id, goal, constraints_text, version, created_at, updated_at)
            VALUES (#{plan.planId}, #{plan.userId}, #{plan.sessionId}, #{plan.goal},
                #{plan.constraints}, #{plan.version}, #{plan.createdAt}, #{plan.updatedAt})
            """)
    int insertPlan(@Param("plan") AgentTaskPlan plan);

    // 每步写入都加入服务的同一个事务，中途失败时前面已经写入的步骤也会回滚。
    @Insert("""
            INSERT INTO agent_task_steps
                (step_id, plan_id, position, description, completion_criteria, status,
                 result_summary, created_at, updated_at)
            VALUES (#{step.stepId}, #{step.planId}, #{step.position}, #{step.description},
                #{step.completionCriteria}, #{step.status}, #{step.resultSummary},
                #{step.createdAt}, #{step.updatedAt})
            """)
    int insertStep(@Param("step") AgentTaskStep step);

    // 同时匹配用户、会话和任务，不提供只凭 planId 读取计划的方法。
    @Select("""
            SELECT plan_id AS planId, user_id AS userId, session_id AS sessionId,
                   goal_number AS goalNumber, goal, constraints_text AS `constraints`, version,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM agent_task_plans
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND plan_id = #{planId}
            """)
    Optional<AgentTaskPlan> findPlan(@Param("userId") Long userId,
                                     @Param("sessionId") Long sessionId,
                                     @Param("planId") String planId);

    // 步骤通过所属计划校验归属，再按顺序读取，避免换个 planId 就查到别人的步骤。
    @Select("""
            SELECT s.step_id AS stepId, s.plan_id AS planId, s.position, s.description,
                   s.completion_criteria AS completionCriteria, s.status,
                   s.result_summary AS resultSummary,
                   s.created_at AS createdAt, s.updated_at AS updatedAt
            FROM agent_task_steps s
            JOIN agent_task_plans p ON p.plan_id = s.plan_id
            WHERE p.user_id = #{userId} AND p.session_id = #{sessionId} AND p.plan_id = #{planId}
            ORDER BY s.position, s.step_id
            """)
    List<AgentTaskStep> findSteps(@Param("userId") Long userId,
                                  @Param("sessionId") Long sessionId,
                                  @Param("planId") String planId);

    // 版本匹配才递增，同时锁住该计划行；锁一直保留到整个更新事务结束。
    @Update("""
            UPDATE agent_task_plans SET version = version + 1, updated_at = #{updatedAt}
            WHERE user_id = #{userId} AND session_id = #{sessionId}
              AND plan_id = #{planId} AND version = #{expectedVersion}
            """)
    int advanceVersion(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                       @Param("planId") String planId, @Param("expectedVersion") long expectedVersion,
                       @Param("updatedAt") LocalDateTime updatedAt);

    // 写事务使用当前读，不能在取得版本后又读取事务早期的旧快照。
    @Select("""
            SELECT plan_id AS planId, user_id AS userId, session_id AS sessionId,
                   goal_number AS goalNumber, goal, constraints_text AS `constraints`, version,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM agent_task_plans
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND plan_id = #{planId}
            FOR UPDATE
            """)
    Optional<AgentTaskPlan> findPlanForUpdate(@Param("userId") Long userId,
                                             @Param("sessionId") Long sessionId,
                                             @Param("planId") String planId);

    // 按固定顺序锁定本任务的步骤，校验与保存期间不让其他写入改变它们。
    @Select("""
            SELECT s.step_id AS stepId, s.plan_id AS planId, s.position, s.description,
                   s.completion_criteria AS completionCriteria, s.status,
                   s.result_summary AS resultSummary,
                   s.created_at AS createdAt, s.updated_at AS updatedAt
            FROM agent_task_steps s
            JOIN agent_task_plans p ON p.plan_id = s.plan_id
            WHERE p.user_id = #{userId} AND p.session_id = #{sessionId} AND p.plan_id = #{planId}
            ORDER BY s.position, s.step_id FOR UPDATE
            """)
    List<AgentTaskStep> findStepsForUpdate(@Param("userId") Long userId,
                                          @Param("sessionId") Long sessionId,
                                          @Param("planId") String planId);

    // 先把旧顺序移出 1～20，再写新顺序，避免交换两步时撞到唯一索引。
    @Update("""
            UPDATE agent_task_steps s JOIN agent_task_plans p ON p.plan_id = s.plan_id
            SET s.position = s.position + #{offset}
            WHERE p.user_id = #{userId} AND p.session_id = #{sessionId} AND p.plan_id = #{planId}
            """)
    int parkStepPositions(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                          @Param("planId") String planId, @Param("offset") int offset);

    // 只更新本任务中的原步骤，不替换固定编号、所属任务或创建时间。
    @Update("""
            UPDATE agent_task_steps s JOIN agent_task_plans p ON p.plan_id = s.plan_id
            SET s.position = #{step.position}, s.description = #{step.description},
                s.completion_criteria = #{step.completionCriteria}, s.status = #{step.status},
                s.result_summary = #{step.resultSummary}, s.updated_at = #{step.updatedAt}
            WHERE p.user_id = #{userId} AND p.session_id = #{sessionId}
              AND p.plan_id = #{step.planId} AND s.step_id = #{step.stepId}
            """)
    int updateStep(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                   @Param("step") AgentTaskStep step);

    // 首次初始化只插一行；并发请求遇到主键时不覆盖归属、版本或当前目标。
    @Insert("""
            INSERT INTO agent_session_focus (user_id, session_id)
            VALUES (#{userId}, #{sessionId}) ON DUPLICATE KEY UPDATE session_id = session_id
            """)
    int ensureFocus(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    // 普通读用于组装一个一致的模型快照。
    @Select("""
            SELECT user_id AS userId, session_id AS sessionId, active_plan_id AS activePlanId,
                   version, next_goal_number AS nextGoalNumber FROM agent_session_focus
            WHERE user_id = #{userId} AND session_id = #{sessionId}
            """)
    Optional<SessionFocusState> findFocus(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    // 切换先锁会话指针，再锁计划；并发修改按同一顺序进入。
    @Select("""
            SELECT user_id AS userId, session_id AS sessionId, active_plan_id AS activePlanId,
                   version, next_goal_number AS nextGoalNumber FROM agent_session_focus
            WHERE user_id = #{userId} AND session_id = #{sessionId} FOR UPDATE
            """)
    Optional<SessionFocusState> lockFocus(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    // 版本匹配才替换当前目标；数据库主键保证每个会话只有一个指针。
    @Update("""
            UPDATE agent_session_focus SET active_plan_id = #{planId}, version = version + 1,
                   next_goal_number = #{nextNumber}
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND version = #{version}
            """)
    int changeFocus(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                    @Param("planId") String planId, @Param("version") long version,
                    @Param("nextNumber") int nextNumber);

    // 目标序号只分配一次；切换回来时保留原引用，不重编号。
    @Update("""
            UPDATE agent_task_plans SET goal_number = #{number}
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND plan_id = #{planId}
              AND goal_number IS NULL
            """)
    int assignGoalNumber(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                         @Param("planId") String planId, @Param("number") int number);

    // 只返回当前会话已登记的目标索引，完整步骤由选中的计划单独加载。
    @Select("""
            SELECT plan_id AS planId, user_id AS userId, session_id AS sessionId, goal_number AS goalNumber,
                   goal, constraints_text AS `constraints`, version
            FROM agent_task_plans WHERE user_id = #{userId} AND session_id = #{sessionId}
              AND goal_number IS NOT NULL ORDER BY goal_number
            """)
    List<AgentTaskPlan> findGoals(@Param("userId") Long userId, @Param("sessionId") Long sessionId);
}
