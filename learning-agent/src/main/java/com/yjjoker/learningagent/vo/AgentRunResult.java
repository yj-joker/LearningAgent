package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.harness.approval.ToolApprovalRequest;
import com.yjjoker.learningagent.harness.model.AgentRunStatus;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import lombok.Getter;

import java.util.List;

// 聊天接口返回结构化状态，前端不用从回答文字中猜测是否需要审批。
@Getter
public class AgentRunResult {
    // runId 标识整个逻辑任务；同一任务可以经历多次审批批次。
    private final String runId;
    private final int batchNumber;
    private final AgentRunStatus status;
    // 普通回答来自模型；待审批提示由后端生成，不额外请求模型。
    private final String answer;
    private final List<ToolApprovalRequest> approvals;
    // 专注模式的正式进度来自数据库快照，前端不需要解析模型回答。
    private final SessionGoalProgress progress;
    // 课程模式返回数据库事实，前端不从回答文字猜当前知识点。
    private final CourseLearningProgressVO courseProgress;

    // 固定返回内容，避免后续修改列表改变已经构造好的响应。
    private AgentRunResult(String runId, int batchNumber, AgentRunStatus status, String answer,
                           List<ToolApprovalRequest> approvals) {
        this(runId, batchNumber, status, answer, approvals, null, null);
    }

    // 统一保存回答、审批状态和可选的真实专注进度。
    private AgentRunResult(String runId, int batchNumber, AgentRunStatus status, String answer,
                           List<ToolApprovalRequest> approvals, SessionGoalProgress progress) {
        this(runId, batchNumber, status, answer, approvals, progress, null);
    }

    // 保存普通、专注和课程三种模式的公开事实视图。
    private AgentRunResult(String runId, int batchNumber, AgentRunStatus status, String answer,
                           List<ToolApprovalRequest> approvals, SessionGoalProgress progress,
                           CourseLearningProgressVO courseProgress) {
        this.runId = runId;
        this.batchNumber = batchNumber;
        this.status = status;
        this.answer = answer;
        this.approvals = List.copyOf(approvals);
        this.progress = progress;
        this.courseProgress = courseProgress;
    }

    // 正常回答不附带主循环的待审批申请。
    public static AgentRunResult completed(String runId, String answer) {
        return new AgentRunResult(runId, 0, AgentRunStatus.COMPLETED, answer, List.of());
    }

    // 专注模式回答附带数据库生成的当前进度；普通聊天传入 null。
    public static AgentRunResult completed(String runId, String answer, SessionGoalProgress progress) {
        return new AgentRunResult(runId, 0, AgentRunStatus.COMPLETED, answer, List.of(), progress);
    }

    // 课程回答附带当前课程快照，便于前端立即刷新课程进度。
    public static AgentRunResult completedCourse(String runId, String answer,
                                                 CourseLearningProgressVO courseProgress) {
        return new AgentRunResult(runId, 0, AgentRunStatus.COMPLETED, answer, List.of(), null, courseProgress);
    }

    // 保存申请成功后才能返回等待状态，不能把内存中的草稿说成已经提交。
    public static AgentRunResult state(String runId, int batchNumber, AgentRunStatus status,
                                       String answer, List<ToolApprovalRequest> approvals) {
        return new AgentRunResult(runId, batchNumber, status, answer, approvals);
    }

    // 等待审批时也返回暂停前的真实进度，前端不根据待审批文字猜测步骤状态。
    public static AgentRunResult state(String runId, int batchNumber, AgentRunStatus status,
                                       String answer, List<ToolApprovalRequest> approvals,
                                       SessionGoalProgress progress) {
        return new AgentRunResult(runId, batchNumber, status, answer, approvals, progress);
    }

    // 审批等待时课程结果由检查点提供；当前写入审批的课程工具仍沿用通用状态接口。
    public static AgentRunResult stateCourse(String runId, int batchNumber, AgentRunStatus status,
                                             String answer, List<ToolApprovalRequest> approvals,
                                             CourseLearningProgressVO courseProgress) {
        return new AgentRunResult(runId, batchNumber, status, answer, approvals, null, courseProgress);
    }
}
