package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RejectRequestDTO {
    @Size(max = 500, message = "拒绝原因不能超过 500 个字符")
    private String reason;
}