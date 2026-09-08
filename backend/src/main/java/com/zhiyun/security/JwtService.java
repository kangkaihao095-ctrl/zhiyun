package com.zhiyun.security;

import com.zhiyun.config.ZhiyunProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {
    private final ZhiyunProperties properties;

    public JwtService(ZhiyunProperties properties) {
        this.properties = properties;
    }

    public String issue(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claim("tid", user.tenantId())
                .claim("email", user.email())
                .claim("name", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getJwt().getExpireHours(), ChronoUnit.HOURS)))
                .signWith(key())
                .compact();
    }

    public AuthUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
        return new AuthUser(
                Long.parseLong(claims.getSubject()),
                ((Number) claims.get("tid")).longValue(),
                String.valueOf(claims.get("email")),
                String.valueOf(claims.get("name"))
        );
    }

    private SecretKey key() {
        byte[] bytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
