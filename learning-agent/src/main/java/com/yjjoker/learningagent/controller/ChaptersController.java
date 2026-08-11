package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.ChaptersDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.ChaptersService;
import com.yjjoker.learningagent.vo.ChaptersVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping
@RestController
@Tag(name="章节接口")
@AllArgsConstructor
@Validated
public class ChaptersController {
    private final ChaptersService chaptersService;
    @Operation(summary = "添加章节")
    @PostMapping("/createChapters")
    public Result<List<ChaptersVO>> createChapters(@Valid @NonNull @RequestBody List<ChaptersDTO> chaptersDTOList) {
          return Result.success(chaptersService.createChapters(chaptersDTOList));
    }
    @Operation(summary = "根据对应的课程ID获取章节列表")
    @GetMapping("/getChaptersByCourseId/{courseId}")
    public Result<List<ChaptersVO>> getChaptersByCourseId(
            @Positive(message = "课程 ID 必须大于 0") @PathVariable Long courseId) {
        List<ChaptersVO> chaptersByCourseId = chaptersService.getChaptersByCourseId(courseId);
        return Result.success(chaptersByCourseId);
    }
    @Operation(summary = "根据章节 ID 批量获取章节")
    @GetMapping("/getChaptersByIds/{ids}")
    public Result<List<ChaptersVO>> getChaptersByIds(
            @PathVariable List<@Positive(message = "章节 ID 必须大于 0") Long> ids) {
        return Result.success(chaptersService.getChaptersByIds(ids));
    }
    @Operation(summary = "修改章节")
    @PutMapping("/updateChapters")
    public Result<List<ChaptersVO>> updateChapters(@Valid @NonNull @RequestBody List<ChaptersDTO> chaptersDTOList) {
        List<ChaptersVO> chaptersVOS = chaptersService.updateChapters(chaptersDTOList);
        return Result.success(chaptersVOS);
    }
    @Operation(summary = "删除章节")
    @DeleteMapping("/deleteChaptersByIds/{ids}")
    public Result<Void> deleteChaptersByIds(
            @PathVariable List<@Positive(message = "章节 ID 必须大于 0") Long> ids) {
        chaptersService.deleteChaptersByIds(ids);
        return Result.success();
}
}
