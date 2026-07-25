package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.vo.CoursesVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/learning-agent/courses")
@AllArgsConstructor
@Slf4j
@Tag(name = "课程接口")
public class CoursesController {
    private final CoursesService coursesService;
    //创建课程
    @PostMapping("/createCourse")
    public Result<CoursesVO> createCourse(@Valid@RequestBody CoursesDTO coursesDTO){
        CoursesVO coursesVO = coursesService.createCourse(coursesDTO);
        return Result.success(coursesVO);
    }
    //发布课程
    @PutMapping("/publishCourse/{courseId}")
    public Result<CoursesVO> publishCourse(@NotNull @PathVariable Long courseId){
        CoursesVO coursesVO = coursesService.publishCourse(courseId);
        return Result.success(coursesVO);
    }
}
