package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.UserService;
import com.yjjoker.learningagent.vo.UserVO;
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
    @RequestMapping("/register")
    public Result<UserVO> register(@NonNull@Valid @RequestBody UserDTO userDTO){
        UserVO userVO = userService.register(userDTO);
        return Result.success(userVO);
    }
}
