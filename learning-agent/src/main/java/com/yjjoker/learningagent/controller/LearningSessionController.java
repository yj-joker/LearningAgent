package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.LearningSessionService;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/learning-agent/learning")
@Tag(name = "学习会话接口")
@AllArgsConstructor
public class LearningSessionController {
    private final LearningSessionService LearningSessionService;
    @PostMapping("/session")
    @Operation(summary = "创建学习会话")
    public Result<LearningSessionVO> createSession(@Valid@RequestBody LearningSessionDTO learningSessionDTO){
        LearningSessionVO LearningSessionVO = LearningSessionService.createSession(learningSessionDTO);
        return Result.success(LearningSessionVO);
    }
}
