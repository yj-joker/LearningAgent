package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalRequest;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.LocalDateTime;

@Mapper
// 审批申请只按申请人查询，避免不同用户互相读取或操作待审批内容。
public interface MemoryApprovalRepository {

    // 保存模型提出但尚未执行的记忆变更。
    @Insert("INSERT INTO memory_approval_requests " +
            "(user_id, session_id, operation, scope, candidate_json, target_snapshot_json, status, " +
            "created_at, updated_at) VALUES " +
            "(#{request.userId}, #{request.sessionId}, #{request.operation}, #{request.scope}, " +
            "#{request.candidateJson}, #{request.targetSnapshotJson}, #{request.status}, " +
            "#{request.createdAt}, #{request.updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "request.id", keyColumn = "id")
    int insert(@Param("request") MemoryApprovalRequest request);

    // 查询单条申请，服务层还会再次检查当前登录用户。
    @Select("SELECT r.*, r.user_id AS userId, r.session_id AS sessionId, " +
            "r.candidate_json AS candidateJson, r.target_snapshot_json AS targetSnapshotJson, " +
            "r.decision_reason AS decisionReason, r.created_at AS createdAt, r.updated_at AS updatedAt, " +
            "r.decided_at AS decidedAt " +
            "FROM memory_approval_requests r " +
            "WHERE r.id = #{id}")
    MemoryApprovalRequest findById(@Param("id") Long id);

    // 只返回当前用户的待处理申请，前端用它生成确认卡片。
    @Select("SELECT r.*, r.user_id AS userId, r.session_id AS sessionId, " +
            "r.candidate_json AS candidateJson, r.target_snapshot_json AS targetSnapshotJson, " +
            "r.decision_reason AS decisionReason, r.created_at AS createdAt, r.updated_at AS updatedAt, " +
            "r.decided_at AS decidedAt " +
            "FROM memory_approval_requests r " +
            "WHERE r.user_id = #{userId} AND r.status = 'PENDING' ORDER BY r.created_at ASC, r.id ASC")
    List<MemoryApprovalRequest> findPendingByUserId(@Param("userId") Long userId);

    // 审批事务使用当前读，等待其他事务结束后不能继续使用旧的 PENDING 快照。
    @Select("SELECT id, user_id AS userId, session_id AS sessionId, operation, scope, " +
            "candidate_json AS candidateJson, target_snapshot_json AS targetSnapshotJson, status, " +
            "decision_reason AS decisionReason, created_at AS createdAt, updated_at AS updatedAt, " +
            "decided_at AS decidedAt FROM memory_approval_requests " +
            "WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    MemoryApprovalRequest lock(@Param("id") Long id, @Param("userId") Long userId);

    // 只有 PENDING 才能变成终态，防止重复点击同一审批按钮。
    @Update("UPDATE memory_approval_requests SET status = #{status}, decision_reason = #{reason}, " +
            "decided_at = #{decidedAt}, updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 'PENDING'")
    int decide(@Param("id") Long id, @Param("userId") Long userId,
               @Param("status") MemoryApprovalStatus status,
               @Param("reason") String reason,
               @Param("decidedAt") LocalDateTime decidedAt,
               @Param("updatedAt") LocalDateTime updatedAt);
}
