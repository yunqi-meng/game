package com.chemera.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.UUID;

/**
 * 请求追踪：给每个 API 调用一个 traceId 放进 MDC 与响应头，配合日志把"玩家说卡了一下"
 * 落到具体一次请求上。静态资源不打日志，避免每台手机进游戏都刷十几行。
 */
@Component
public class RequestTraceInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger("chemera.access");
    public static final String HEADER = "X-Request-Id";
    private static final String AT = "chemera.req.start";
    /** 入口文档必须每次回源：它带的是 ?v=N 资源清单，缓存住就等于把玩家钉在旧版本上。 */
    private static final Set<String> ENTRIES =
            Set.of("/", "/index.html", "/admin", "/admin/", "/admin/index.html");

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
        String id = req.getHeader(HEADER);
        if (id == null || id.isBlank() || id.length() > 64) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        MDC.put("rid", id);
        res.setHeader(HEADER, id);
        if (ENTRIES.contains(req.getRequestURI())) res.setHeader("Cache-Control", "no-cache");
        req.setAttribute(AT, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object h, Exception ex) {
        try {
            String p = req.getRequestURI();
            boolean api = p.startsWith("/api/") || p.startsWith("/admin/api/");
            Object st = req.getAttribute(AT);
            long ms = st == null ? -1 : (System.nanoTime() - (Long) st) / 1_000_000;
            if (api) {
                log.info("{} {} {} {}ms {}", req.getMethod(), p, res.getStatus(), ms, AuthContext.who(req));
                if (ms > 1000) log.warn("慢请求 {} {} {}ms", req.getMethod(), p, ms);
            }
        } finally {
            MDC.remove("rid");
        }
    }
}
