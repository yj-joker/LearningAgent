package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.CreateLearningPlanDraftRequest;
import com.yjjoker.learningagent.dto.UpdateLearningPlanDraftRequest;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.service.LearningPlanDraftService;
import com.yjjoker.learningagent.vo.LearningPlanDraftVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 提供学习计划草案的手动入口；接口和 Agent 最终共用同一份草案数据。
@RestController
@RequestMapping("/learning-agent/learning-plans/drafts")
@RequiredArgsConstructor
@Tag(name = "学习计划草案")
public class LearningPlanDraftController {
    private final LearningPlanDraftService service;

    // 手动创建草案；来源固定为 MANUAL，不能由请求体伪造。
    @PostMapping
    @Operation(summary = "创建学习计划草案")
    public Result<LearningPlanDraftVO> create(@Valid @RequestBody CreateLearningPlanDraftRequest request) {
        return Result.success(service.createManual(request));
    }

    // 返回当前登录用户的全部未生效草案。
    @GetMapping
    @Operation(summary = "查看学习计划草案列表")
    public Result<List<LearningPlanDraftVO>> list() {
        return Result.success(service.listCurrentUser());
    }

    // 返回一份草案的主体和完整步骤。
    @GetMapping("/{draftRef}")
    @Operation(summary = "查看学习计划草案")
    public Result<LearningPlanDraftVO> get(@PathVariable String draftRef) {
        return Result.success(service.getCurrentUser(draftRef));
    }

    // 手动更新必须携带 expectedVersion，防止覆盖 Agent 或其他页面刚保存的版本。
    @PutMapping("/{draftRef}")
    @Operation(summary = "更新学习计划草案")
    public Result<LearningPlanDraftVO> update(@PathVariable String draftRef,
                                              @Valid @RequestBody UpdateLearningPlanDraftRequest request) {
        if (request.getDraftRef() != null && !request.getDraftRef().isBlank()
                && !request.getDraftRef().equals(draftRef)) {
            throw new ClientDataErrorException("请求体中的 draftRef 与路径不一致");
        }
        return Result.success(service.updateManual(draftRef, request));
    }
}
