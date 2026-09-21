package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.AgentChatRequest;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.harness.AgentHarnessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
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
    public Result<String> chat(@Valid @RequestBody AgentChatRequest request) {
        return Result.success(agentHarnessService.run(request.getSessionId(), request.getUserMessage()));
    }
}
