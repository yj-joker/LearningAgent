package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.UserTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private String avatarUrl;
    private UserTypeEnum userType;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;
}
