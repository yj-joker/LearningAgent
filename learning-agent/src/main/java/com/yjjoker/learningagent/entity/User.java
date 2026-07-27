package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private String avatarUrl;
    private UserRoleEnum role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
