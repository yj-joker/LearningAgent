package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.UpdateSessionLearningPlanBindingRequest;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalService;
import com.yjjoker.learningagent.vo.SessionLearningPlanBindingVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 提供专注会话与跨会话 ACTIVE 学习计划的关联入口。
@RestController
@RequestMapping("/agent/sessions")
@RequiredArgsConstructor
@Tag(name = "会话学习计划管理")
public class AgentSessionLearningPlanController {
    private final SessionGoalService sessionGoalService;

    // 返回会话当前关联；会话尚未初始化专注目标时也会返回空引用。
    @GetMapping("/{sessionId}/learning-plan")
    @Operation(summary = "获取会话学习计划绑定")
    public Result<SessionLearningPlanBindingVO> get(@Positive @PathVariable Long sessionId) {
        return Result.success(sessionGoalService.getLearningPlanBinding(sessionId));
    }

    // 绑定 ACTIVE 计划或传空 draftRef 解除绑定；后端验证所有权和关联版本。
    @PutMapping("/{sessionId}/learning-plan")
    @Operation(summary = "更新会话学习计划绑定")
    public Result<SessionLearningPlanBindingVO> update(
            @Positive @PathVariable Long sessionId,
            @Valid @RequestBody UpdateSessionLearningPlanBindingRequest request) {
        return Result.success(sessionGoalService.bindLearningPlan(
                sessionId, request.getDraftRef(), request.getExpectedBindingVersion()));
    }
}
