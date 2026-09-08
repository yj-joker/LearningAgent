package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用户分页列表项。ID 使用字符串返回，避免 JavaScript 大整数精度丢失。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPageItemVO {
    //TODO 将id改成long类型
    private String id;
    private String username;
    private String avatarUrl;
    private UserRoleEnum role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
