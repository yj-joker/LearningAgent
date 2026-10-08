package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 专注页面刷新或选中历史会话后，从数据库恢复当前目标和步骤状态。
@RestController
@RequestMapping("/agent/sessions")
@RequiredArgsConstructor
@Validated
@Tag(name = "会话学习进度")
public class AgentSessionProgressController {
    private final SessionGoalService sessionGoalService;

    // 只读已保存的进度；归属、模式和会话状态由服务校验，空结果表示尚未创建目标。
    @GetMapping("/{sessionId}/progress")
    @Operation(summary = "查看专注会话学习进度")
    public Result<SessionGoalProgress> get(@Positive @PathVariable Long sessionId) {
        return Result.success(sessionGoalService.getProgress(sessionId));
    }
}
