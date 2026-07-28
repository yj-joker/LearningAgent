package com.yjjoker.learningagent.page;

import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
public class UserPageRequest extends PageRequest {

    @Size(max = 64, message = "用户名长度不能超过 64 个字符")
    private String username;

    private UserRoleEnum role;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdAtStart;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdAtEnd;

    @AssertTrue(message = "创建时间开始值不能晚于结束值")
    public boolean isCreatedAtRangeValid() {
        return createdAtStart == null
                || createdAtEnd == null
                || !createdAtStart.isAfter(createdAtEnd);
    }
}
