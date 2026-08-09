package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import lombok.Data;

@Data
public class UserVO {
    private Long id;
    private String username;
    private String avatarUrl;
    private String token;
    private UserRoleEnum role;
}
