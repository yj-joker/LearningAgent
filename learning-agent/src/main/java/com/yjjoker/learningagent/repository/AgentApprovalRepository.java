package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.harness.approval.AgentApprovalRun;
import com.yjjoker.learningagent.harness.approval.ToolApprovalRequest;
import org.apache.ibatis.annotations.*;
import java.util.List;

// 所有查询都带登录用户范围；同一任务先锁运行行，再修改其工具审批。
@Mapper
public interface AgentApprovalRepository {
    String RUN_COLUMNS = "run_id AS runId,user_id AS userId,session_id AS sessionId," +
            "batch_number AS batchNumber,status,checkpoint_json AS checkpointJson,answer";

    // 首次暂停才创建运行记录，检查点与申请由服务层事务一起提交。
    @Insert("INSERT INTO agent_approval_runs(run_id,user_id,session_id,batch_number,status,checkpoint_json) " +
            "VALUES(#{runId},#{userId},#{sessionId},#{batchNumber},'WAITING_APPROVAL',#{checkpointJson})")
    int insertRun(AgentApprovalRun run);

    // 只读取当前用户的任务，接口不会返回 checkpointJson。
    @Select("SELECT " + RUN_COLUMNS + " FROM agent_approval_runs WHERE run_id=#{runId} AND user_id=#{userId}")
    AgentApprovalRun find(@Param("runId") String runId, @Param("userId") Long userId);

    // 短事务内锁住运行行，串行处理同一任务的审批决定和恢复抢占。
    @Select("SELECT " + RUN_COLUMNS + " FROM agent_approval_runs WHERE run_id=#{runId} AND user_id=#{userId} FOR UPDATE")
    AgentApprovalRun lock(@Param("runId") String runId, @Param("userId") Long userId);

    // 继续执行时再次遇到审批，更新同一任务的检查点并增加批次号。
    @Update("UPDATE agent_approval_runs SET status='WAITING_APPROVAL',batch_number=#{batchNumber}," +
            "checkpoint_json=#{checkpointJson},updated_at=CURRENT_TIMESTAMP(6) " +
            "WHERE run_id=#{runId} AND user_id=#{userId} AND status='RUNNING'")
    int pauseAgain(AgentApprovalRun run);

    // 每项申请直接关联任务和批次，不再需要单独的关联表。
    @Insert("INSERT INTO agent_tool_approvals(run_id,batch_number,tool_call_id,tool_name,arguments_json,reason,status) " +
            "VALUES(#{runId},#{batchNumber},#{toolCallId},#{toolName},#{arguments},#{reason},'PENDING')")
    int insertApproval(ToolApprovalRequest request);

    // 读取当前批次的审批内容；是否已经全部审完由下面的 pending 锁定查询判断。
    @Select("SELECT run_id AS runId,batch_number AS batchNumber,tool_call_id AS toolCallId," +
            "tool_name AS toolName,arguments_json AS arguments,reason,status,decision_reason AS decisionReason " +
            "FROM agent_tool_approvals WHERE run_id=#{runId} AND batch_number=#{batchNumber} ORDER BY tool_call_id")
    List<ToolApprovalRequest> approvals(@Param("runId") String runId, @Param("batchNumber") int batchNumber);

    // 锁定查询用于决定最后一项是否已处理，不能依赖普通快照读。
    @Select("SELECT tool_call_id FROM agent_tool_approvals WHERE run_id=#{runId} " +
            "AND batch_number=#{batchNumber} AND status='PENDING' FOR UPDATE")
    List<String> pending(@Param("runId") String runId, @Param("batchNumber") int batchNumber);

    // 只有当前待处理申请可以修改；旧页面提交旧批次不能授权新工具调用。
    @Update("UPDATE agent_tool_approvals SET status=#{status},decision_reason=#{reason},decided_at=CURRENT_TIMESTAMP(6) " +
            "WHERE run_id=#{runId} AND batch_number=#{batchNumber} AND tool_call_id=#{callId} AND status='PENDING'")
    int decide(@Param("runId") String runId, @Param("batchNumber") int batchNumber,
               @Param("callId") String callId, @Param("status") String status, @Param("reason") String reason);

    // 状态条件就是比较并交换：只有一个请求可以从待恢复变成执行中。
    @Update("UPDATE agent_approval_runs SET status=#{next},updated_at=CURRENT_TIMESTAMP(6) " +
            "WHERE run_id=#{runId} AND user_id=#{userId} AND status=#{expected}")
    int transition(@Param("runId") String runId, @Param("userId") Long userId,
                   @Param("expected") String expected, @Param("next") String next);

    // 完成状态、答案和检查点清理一起提交；保留空 JSON 以兼容现有非空列，不再保留上下文正文。
    // 服务层在同一事务保存聊天消息，保存失败时清理也会回滚，重复恢复只读取 answer。
    @Update("UPDATE agent_approval_runs SET status='COMPLETED',answer=#{answer},checkpoint_json=JSON_OBJECT(),updated_at=CURRENT_TIMESTAMP(6) " +
            "WHERE run_id=#{runId} AND user_id=#{userId} AND status='RUNNING'")
    int complete(@Param("runId") String runId, @Param("userId") Long userId, @Param("answer") String answer);

    // 暂停或恢复期间不允许另开普通聊天覆盖同一会话的执行顺序。
    @Select("SELECT COUNT(*) FROM agent_approval_runs WHERE user_id=#{userId} AND session_id=#{sessionId} " +
            "AND status IN ('WAITING_APPROVAL','APPROVAL_RESOLVED','RUNNING')")
    int unfinished(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    // 页面丢失 runId 时按会话找回暂停任务，返回对象仍只在服务层使用。
    @Select("SELECT " + RUN_COLUMNS + " FROM agent_approval_runs WHERE user_id=#{userId} AND session_id=#{sessionId} " +
            "AND status IN ('WAITING_APPROVAL','APPROVAL_RESOLVED','RUNNING') ORDER BY created_at DESC LIMIT 1")
    AgentApprovalRun active(@Param("userId") Long userId, @Param("sessionId") Long sessionId);
}
