package com.chemera.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** 访问令牌签发/校验（HS256）。typ=user 为游戏用户，typ=admin 为后台管理员。 */
@Service
public class JwtService {
    private final SecretKey key;
    private final long accessTtlMs;

    public JwtService(@Value("${chemera.jwt.secret}") String secret,
                      @Value("${chemera.jwt.access-ttl-minutes:120}") long accessTtlMinutes) {
        byte[] b = secret.getBytes(StandardCharsets.UTF_8);
        if (b.length < 32) throw new IllegalStateException("chemera.jwt.secret 至少需 32 字节");
        this.key = Keys.hmacShaKeyFor(b);
        this.accessTtlMs = accessTtlMinutes * 60_000L;
    }

    public String issue(long subjectId, String type, String role) {
        return issue(subjectId, type, role, null);
    }

    /** name 会写进 uname 声明，供审计日志识别操作者；无名称时留空。 */
    public String issue(long subjectId, String type, String role, String name) {
        Date now = new Date();
        var b = Jwts.builder()
                .subject(String.valueOf(subjectId))
                .claim("typ", type)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTtlMs));
        if (name != null && !name.isBlank()) b.claim("uname", name);
        return b.signWith(key).compact();
    }

    /** 解析并校验类型；失败抛 JwtException。 */
    public Claims verify(String token, String expectedType) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        if (!expectedType.equals(c.get("typ", String.class))) {
            throw new JwtException("令牌类型不符");
        }
        return c;
    }

    public long accessTtlMs() { return accessTtlMs; }
}
