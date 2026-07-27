package com.yjjoker.learningagent.utils;

import com.yjjoker.learningagent.config.JwtProperties;
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

    public JwtService(JwtProperties properties) {
        byte[] keyBytes = Decoders.BASE64.decode(properties.secret());
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMillis = properties.expirationMillis();
    }

    public String createToken(Long userId, String userType) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(userId.toString())
                .claim("userType", userType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMillis)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public Long parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return Long.valueOf(claims.getSubject());
    }
}