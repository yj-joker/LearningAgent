package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.ToolApprovalDecisionRequest;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.harness.approval.AgentApprovalService;
import com.yjjoker.learningagent.harness.service.AgentHarnessService;
import com.yjjoker.learningagent.vo.AgentRunResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// 与业务工具无关的审批 API；登录用户只能查询和恢复自己的任务。
@RestController
@RequestMapping("/agent/runs")
@RequiredArgsConstructor
public class AgentApprovalController {
    private final AgentApprovalService approvals;
    private final AgentHarnessService harness;

    // 浏览器不必记住 runId；服务端按当前用户和会话找回尚未结束的任务。
    @GetMapping
    public Result<AgentRunResult> active(@RequestParam Long sessionId) {
        return Result.success(approvals.active(sessionId));
    }

    // 页面刷新后用 runId 查询当前批次，不需要再次询问模型。
    @GetMapping("/{runId}")
    public Result<AgentRunResult> get(@PathVariable String runId) {
        return Result.success(approvals.get(runId));
    }

    // 批准只保存决定；请求体不允许指定执行参数，执行始终使用检查点里的原请求。
    @PostMapping("/{runId}/approvals/{batchNumber}/{toolCallId}")
    public Result<AgentRunResult> decide(@PathVariable String runId, @PathVariable int batchNumber,
                                       @PathVariable String toolCallId,
                                       @Valid @RequestBody ToolApprovalDecisionRequest request) {
        return Result.success(approvals.decide(runId, batchNumber, toolCallId,
                request.getApproved(), request.getReason()));
    }

    // 全批审批结束后继续原任务；不会先要求模型重新规划相同工具。
    @PostMapping("/{runId}/resume")
    public Result<AgentRunResult> resume(@PathVariable String runId) {
        return Result.success(harness.resume(runId));
    }
}
