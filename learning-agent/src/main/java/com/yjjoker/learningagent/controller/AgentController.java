package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.AgentChatRequest;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.harness.service.AgentHarnessService;
import com.yjjoker.learningagent.vo.AgentRunResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent")
@AllArgsConstructor
@Tag(name = "智能体")
public class AgentController {

    private final AgentHarnessService agentHarnessService;

    @PostMapping("/chat")
    @Operation(summary = "智能体聊天")
    // 正常回答和等待审批共用一个接口，前端通过 status 区分，不需要再问一次模型。
    public Result<AgentRunResult> chat(@Valid @RequestBody AgentChatRequest request) {
        // 请求模式仅兼容旧客户端，服务从会话读取实际模式；审批恢复沿用原检查点。
        return Result.success(agentHarnessService.run(
                request.getSessionId(), request.getUserMessage(), request.getMode()));
    }

    @DeleteMapping("/delete/{id}")
    @Operation(summary = "删除会话")
    public Result<Void> deleteSession(@Positive(message = "学习会话 ID 必须大于 0")
                                      @PathVariable Long id) {
        agentHarnessService.deleteSession(id);
        return Result.success();
    }
}
