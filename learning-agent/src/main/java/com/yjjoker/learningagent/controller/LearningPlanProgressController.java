package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.LearningPlanProgressService;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 提供长期学习计划进度的只读接口；完成进度仍必须走后续审批闭环。
@RestController
@RequestMapping("/learning-agent/learning-plans/drafts")
@RequiredArgsConstructor
@Tag(name = "长期学习计划进度")
public class LearningPlanProgressController {
    private final LearningPlanProgressService service;

    // 返回计划内容、版本和每个步骤的当前进度证据。
    @GetMapping("/{draftRef}/progress")
    @Operation(summary = "查看学习计划步骤进度")
    public Result<LearningPlanProgressVO> get(@NotBlank @PathVariable String draftRef) {
        return Result.success(service.loadCurrentUser(draftRef));
    }
}
