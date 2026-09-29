package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.notification.ApprovalHandshakeInterceptor;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.utils.JwtService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// 领票接口继续经过原有登录拦截器，匿名用户不能申请连接。
@RestController
@RequiredArgsConstructor
public class ApprovalNotificationController {
    private final ApprovalHandshakeInterceptor tickets;
    private final JwtService jwtService;

    // 返回 30 秒内有效的一次性票据；禁止浏览器或代理缓存认证响应。
    @PostMapping("/agent/approvals/socket-ticket")
    public ResponseEntity<Result<Map<String, String>>> ticket(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        String ticket = tickets.issue(BaseContext.getCurrentId(), jwtService.parseExpiration(authorization.substring(7)));
        return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Result.success(Map.of("ticket", ticket)));
    }
}
