package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.dto.StandaloneLearningSessionDTO;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.LearningSessionService;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/learning-agent/learning")
@Tag(name = "学习会话接口")
@AllArgsConstructor
public class LearningSessionController {
    private final LearningSessionService learningSessionService;

    // 原课程学习入口继续校验课程编号与学习目标，不影响已有调用。
    @PostMapping("/session")
    @Operation(summary = "创建学习会话")
    public Result<LearningSessionVO> createSession(@Valid@RequestBody LearningSessionDTO learningSessionDTO){
        return Result.success(learningSessionService.createSession(learningSessionDTO));
    }

    // 问答与专注页面首次发送时创建独立会话，课程编号不需要填写。
    @PostMapping("/standalone-session")
    @Operation(summary = "创建独立问答或专注会话")
    public Result<LearningSessionVO> createStandaloneSession(@Valid @RequestBody StandaloneLearningSessionDTO request) {
        return Result.success(learningSessionService.createStandaloneSession(request));
    }

    // 展示当前用户全部未删除会话，归属过滤由服务和数据库查询保证。
    @GetMapping("/sessions")
    @Operation(summary = "获取当前用户全部学习会话")
    public Result<List<LearningSessionVO>> findSessions() {
        return Result.success(learningSessionService.findSessions());
    }

    // 完成会话后返回数据库对应的新状态，前端可立即更新列表。
    @PutMapping("/session/completed/{learningSessionId}")
    @Operation(summary = "完成学习会话")
    public Result<LearningSessionVO> completeSession(@NonNull @PathVariable Long learningSessionId){
        LearningSessionVO learningSessionVO = learningSessionService.changeSessionStatus(learningSessionId);
        return Result.success(learningSessionVO);
    }

    // mode 可选：新页面避免混合不同模式历史，旧无参调用保留兼容行为。
    @GetMapping("/session/{learningSessionId}/messages")
    @Operation(summary = "获取学习会话历史消息")
    public Result<List<LearningSessionMessage>> findSessionMessages(
            @NonNull @PathVariable Long learningSessionId,
            @RequestParam(required = false) AgentMode mode) {
        return Result.success(learningSessionService.findSessionMessages(learningSessionId, mode));
    }
}
