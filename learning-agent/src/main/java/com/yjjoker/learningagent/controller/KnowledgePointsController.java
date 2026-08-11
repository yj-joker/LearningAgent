package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.KnowledgePointsDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.KnowledgePointsService;
import com.yjjoker.learningagent.vo.KnowledgePointsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/knowledgePoints")
@RequiredArgsConstructor
@Validated
@Tag(name = "知识点接口")
public class KnowledgePointsController {
    private final KnowledgePointsService knowledgePointsService;

    @PostMapping("/createKnowledgePoint")
    @Operation(summary = "创建知识点")
    public Result<List<KnowledgePointsVO>> createKnowledgePoint(
            @Valid @RequestBody List<KnowledgePointsDTO> knowledgePointsDTOList) {
        return Result.success(knowledgePointsService.createKnowledgePoint(knowledgePointsDTOList));
    }

    @GetMapping("/{ids}")
    @Operation(summary = "根据 ID 查询知识点")
    public Result<List<KnowledgePointsVO>> getKnowledgePointById(
            @PathVariable List<@Positive(message = "知识点 ID 必须大于 0") Long> ids) {
        return Result.success(knowledgePointsService.getKnowledgePointByIds(ids));
    }

    @GetMapping("/chapter/{chapterId}")
    @Operation(summary = "查询章节下的知识点")
    public Result<List<KnowledgePointsVO>> getKnowledgePointsByChapterId(
            @Positive(message = "章节 ID 必须大于 0") @PathVariable Long chapterId) {
        return Result.success(knowledgePointsService.getKnowledgePointsByChapterId(chapterId));
    }

    @PutMapping("/updateKnowledgePoints")
    @Operation(summary = "更新知识点")
    public Result<List<KnowledgePointsVO>> updateKnowledgePoint(@Valid @RequestBody List<KnowledgePointsDTO> knowledgePointsDTOList) {
        return Result.success(knowledgePointsService.updateKnowledgePoint(knowledgePointsDTOList));
    }

    @DeleteMapping("/{ids}")
    @Operation(summary = "删除知识点")
    public Result<Void> deleteKnowledgePoint(
            @PathVariable List<@Positive(message = "知识点 ID 必须大于 0") Long> ids) {
        knowledgePointsService.deleteKnowledgePoint(ids);
        return Result.success();
    }
}
