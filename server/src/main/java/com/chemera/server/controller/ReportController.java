package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.security.AuthContext;
import com.chemera.server.security.RateGuard;
import com.chemera.server.service.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 玩家的举报入口（G2）。
 *
 * <p>整条通道原来只差这一个类：表、mapper、后台审核页都在，唯独没有人能把一行写进 {@code report}。
 * 所以这里做的事很少——把身份、两道防洪（{@link RateGuard#checkReport}：IP 一道、uid 一道）
 * 和参数原样交给 {@link ReportService}，业务判定全留在那个可单测的服务里。
 *
 * <p>需要 JWT：举报是可追责的行为，匿名举报表会被当成垃圾桶。也因此这条路径必须出现在
 * {@code WebConfig} 的 {@code AuthInterceptor} 名单里（G2 的另一处改动）。
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {
    private final ReportService reports;
    private final RateGuard guard;

    public ReportController(ReportService reports, RateGuard guard) {
        this.reports = reports; this.guard = guard;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> report(@RequestBody(required = false) Map<String, Object> b,
                                                   HttpServletRequest req) {
        long uid = AuthContext.uid(req);
        guard.checkReport(guard.ipOf(req), uid);
        Map<String, Object> body = b == null ? Map.of() : b;
        Long target = null;
        if (body.get("target") instanceof Number n) target = n.longValue();
        String kind = body.get("kind") == null ? null : String.valueOf(body.get("kind"));
        String ref = body.get("ref") == null ? null : String.valueOf(body.get("ref"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return ApiResponse.ok(reports.file(uid, kind, target, ref, reason));
    }
}
