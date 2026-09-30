package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.RejectRequestDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalView;
import com.yjjoker.learningagent.harness.memory.service.MemoryApprovalService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 提供前端展示审批申请、批准和拒绝的最小 HTTP 接口。
@RestController
@RequestMapping("/agent/memory-approvals")
@AllArgsConstructor
@Tag(name = "用户审批接口")
public class MemoryApprovalController {
    private final MemoryApprovalService service;

    // 返回当前登录用户仍未处理的申请。
    @GetMapping
    @Tag(name = "获取待处理的审批申请")
    public Result<List<MemoryApprovalView>> pending() {
        return Result.success(service.pending());
    }

    // 用户明确点击同意后执行申请保存的修改。
    @PostMapping("/{id}/approve")
    @Tag(name = "批准审批申请")
    public Result<MemoryApprovalView> approve(@PathVariable Long id) {
        return Result.success(service.approve(id));
    }

    // 用户拒绝只关闭申请，不触碰记忆数据。
    @PostMapping("/{id}/reject")
    @Tag(name = "拒绝审批申请")
    public Result<MemoryApprovalView> reject(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) RejectRequestDTO request) {
        return Result.success(service.reject(id, request == null ? null : request.getReason()));
    }


}
