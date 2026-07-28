package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.annotation.AdminAnnotation;
import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.page.PageResult;
import com.yjjoker.learningagent.page.UserPageRequest;
import com.yjjoker.learningagent.service.UserService;
import com.yjjoker.learningagent.vo.UserPageItemVO;
import com.yjjoker.learningagent.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/learning-agent/user")
@AllArgsConstructor
@Tag(name = "用户模块接口")
public class UserController {
    private final UserService userService;
    //用户注册
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<UserVO> register(@NonNull@Valid @RequestBody UserDTO userDTO){
        UserVO userVO = userService.register(userDTO);
        return Result.success(userVO);
    }
    //用户登录
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public Result<UserVO> login(@NonNull@Valid @RequestBody UserDTO userDTO){
        UserVO userVO = userService.login(userDTO);
        return Result.success(userVO);
    }
    //管理员分页查询用户，查询条件均为可选参数
    @GetMapping("/pageQuery")
    @AdminAnnotation
    @Operation(summary = "分页查询用户")
    public Result<PageResult<UserPageItemVO>> query(
            @Valid @ParameterObject @ModelAttribute UserPageRequest request) {
        return Result.success(userService.query(request));
    }
}
