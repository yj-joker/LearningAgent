package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

// 用户只能选择批准或拒绝，不能在审批接口偷偷替换工具名或参数。
@Data
public class ToolApprovalDecisionRequest {
    @NotNull(message = "请选择批准或拒绝")
    private Boolean approved;
    @Size(max = 500, message = "审批理由不能超过 500 字")
    private String reason;
}

