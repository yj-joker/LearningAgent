package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.CourseLearningProgressService;
import com.yjjoker.learningagent.vo.CourseLearningProgressVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 提供课程模式的当前知识点和全部知识点进度，只读不改变掌握状态。
@RestController
@RequestMapping("/learning-agent/learning/course-progress")
@RequiredArgsConstructor
@Validated
@Tag(name = "课程学习进度")
public class CourseLearningProgressController {
    private final CourseLearningProgressService service;

    // 用户显式开始课程学习，重复调用保留原进度和快照。
    @PostMapping("/{sessionId}")
    @Operation(summary = "初始化课程学习进度")
    public Result<CourseLearningProgressVO> initialize(@Positive @PathVariable Long sessionId) {
        return Result.success(service.initialize(sessionId));
    }

    // 读取已有课程快照和当前知识点，GET 不初始化也不修改掌握状态。
    @GetMapping("/{sessionId}")
    @Operation(summary = "查看课程学习进度")
    public Result<CourseLearningProgressVO> get(@Positive @PathVariable Long sessionId) {
        return Result.success(service.load(sessionId));
    }
}
