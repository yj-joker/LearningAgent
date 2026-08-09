package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.annotation.AdminAnnotation;
import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.vo.CoursesVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/learning-agent/courses")
@AllArgsConstructor
@Slf4j
@Validated
@Tag(name = "课程接口")
public class CoursesController {
    private final CoursesService coursesService;
    //创建课程
    @PostMapping("/createCourse")
    @Operation(summary = "创建课程")
    public Result<CoursesVO> createCourse(@Valid@RequestBody CoursesDTO coursesDTO){
        CoursesVO coursesVO = coursesService.createCourse(coursesDTO);
        return Result.success(coursesVO);
    }
    //用户发布课程
    @PatchMapping("/publishCourse/{courseId}")
    @Operation(summary = "发布课程")
    public Result<CoursesVO> publishCourse(@NotNull @Positive @PathVariable Long courseId){
        CoursesVO coursesVO = coursesService.publishCourse(courseId);
        return Result.success(coursesVO);
    }
    //审核通过
    @PatchMapping("/passCourse/{courseId}")
    @Operation(summary = "审核通过课程")
    @AdminAnnotation
    public Result<CoursesVO> passCourse(@NotNull @Positive @PathVariable Long courseId){
        CoursesVO coursesVO = coursesService.passCourse(courseId);
        return Result.success(coursesVO);
    }
    //审核未通过或者下架课程
    @PatchMapping("/rejectCourse/{courseId}")
    @Operation(summary = "审核未通过或下架课程")
    @AdminAnnotation
    public Result<CoursesVO> rejectCourse(@NotNull @Positive @PathVariable Long courseId){
        CoursesVO coursesVO = coursesService.rejectCourse(courseId);
        return Result.success(coursesVO);
    }
}
