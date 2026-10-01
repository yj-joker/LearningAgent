package com.yjjoker.learningagent.notification;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 仅在后端短暂保存连接凭据；用户身份由登录接口验证，不能由浏览器指定。
@Getter
@AllArgsConstructor
public class ApprovalSocketTicket {
    private final Long userId;
    // 票据必须在签发后 30 秒内使用，而且只能使用一次。
    private final Instant expiresAt;
    // 长连接也要在原登录令牌失效后关闭。
    private final Instant authenticationExpiresAt;
}
