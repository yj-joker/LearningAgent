package com.yjjoker.learningagent.utils;

import com.yjjoker.learningagent.config.JwtProperties;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtService {
    private final SecretKey secretKey;
    private final long expirationMillis;

    // 从配置还原签名密钥，不在日志中记录密钥内容。
    public JwtService(JwtProperties properties) {
        byte[] keyBytes = Decoders.BASE64.decode(properties.secret());
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMillis = properties.expirationMillis();
    }

    // 登录成功后签发包含身份、角色和失效时间的令牌。
    public String createToken(Long userId, UserRoleEnum role) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMillis)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    // 验证签名和有效期，再读取用户身份，不能直接相信浏览器提交的用户编号。
    public Long parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return Long.valueOf(claims.getSubject());
    }

    // 验证令牌并读取失效时间，WebSocket 不能比登录令牌活得更久。
    public Instant parseExpiration(String token) {
        return Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload().getExpiration().toInstant();
    }
    // 验证令牌后读取角色，供现有 HTTP 权限校验使用。
    public UserRoleEnum parseUserRole(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return UserRoleEnum.valueOf(claims.get("role").toString());
    }
}
