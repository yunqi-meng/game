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
        return issue(subjectId, type, role, null, null);
    }

    /** name 会写进 uname 声明，供审计日志识别操作者；无名称时留空。 */
    public String issue(long subjectId, String type, String role, String name) {
        return issue(subjectId, type, role, name, null);
    }

    /**
     * 带会话号的签发（A6）。
     *
     * <p>{@code sid} 是 {@code user_session} 行的主键：令牌从此不再只是"我持有过一次正确口令"的证明，
     * 而是"我手上这一次登录还活着"的凭据。校验侧每请求按主键查一行，撤销（封禁、改密、重置口令、
     * 注销、轮换）因此在同一个请求里生效，而不是等访问令牌自然过期的 ≤2h 空窗。
     *
     * <p>后台令牌不带 sid：{@code AdminInterceptor} 本来就每请求回查 {@code admin_user} 行，
     * 角色与停用都以库为准，即时性已经有了，没必要再造一套。
     */
    public String issue(long subjectId, String type, String role, String name, Long sid) {
        Date now = new Date();
        var b = Jwts.builder()
                .subject(String.valueOf(subjectId))
                .claim("typ", type)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTtlMs));
        if (name != null && !name.isBlank()) b.claim("uname", name);
        if (sid != null) b.claim("sid", sid);
        return b.signWith(key).compact();
    }

    /** 会话号；老令牌（本轮上线前签发的）没有这个声明，返回 null 由校验侧当作失效处理。 */
    public Long sid(Claims c) {
        Number n = c.get("sid", Number.class);
        return n == null ? null : n.longValue();
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
