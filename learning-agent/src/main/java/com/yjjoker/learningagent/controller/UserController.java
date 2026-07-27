package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.UserService;
import com.yjjoker.learningagent.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/learning-agent/user")
@AllArgsConstructor
public class UserController {
    private final UserService userService;
    //用户注册
    @RequestMapping("/register")
    @Operation(summary = "用户注册")
    public Result<UserVO> register(@NonNull@Valid @RequestBody UserDTO userDTO){
        UserVO userVO = userService.register(userDTO);
        return Result.success(userVO);
    }
    //用户登录
    @RequestMapping("/login")
    @Operation(summary = "用户登录")
    public Result<UserVO> login(@NonNull@Valid @RequestBody UserDTO userDTO){
        UserVO userVO = userService.login(userDTO);
        return Result.success(userVO);
    }
}
