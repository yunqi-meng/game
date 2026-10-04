package com.chemera.server.service;

import com.chemera.server.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 登录态表清理：轮换上线后 {@code user_session} 只会单调增长（每次刷新留一行已撤销记录），
 * 没有清理就会变成只增不减的垃圾表。过期行即时删，已撤销行留一段追溯窗口供重用取证。
 */
@Component
public class SessionJanitor {
    private static final Logger log = LoggerFactory.getLogger(SessionJanitor.class);

    private final SessionMapper sessions;
    private final long keepRevokedDays;

    public SessionJanitor(SessionMapper sessions,
                          @Value("${chemera.session.revoked-retention-days:7}") long keepRevokedDays) {
        this.sessions = sessions; this.keepRevokedDays = keepRevokedDays;
    }

    @Scheduled(initialDelay = 60_000L, fixedDelay = 24 * 3600_000L)
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        int n = sessions.purgeStale(now, now.minusDays(keepRevokedDays));
        if (n > 0) log.info("user_session 清理 {} 行（过期 + 撤销超 {} 天）", n, keepRevokedDays);
    }
}
