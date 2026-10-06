package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.game.CurfewGuard;
import com.chemera.server.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 青少年模式的只读状态：客户端拿它渲染倒计时页，不拿它当许可。
 *
 * <p>这里的字段全是展示用的——真正的拦截在 {@link CurfewGuard#assertAllowed}，发生在
 * {@code GameService.act} 的第一行。就算有人把这个接口的返回改成 {@code allowed:true}，
 * 他手上的玩法请求照样 403，一个奖励也拿不到。之所以还是要提供它：让玩家知道自己还要等多久，
 * 比让他反复点按钮然后看到一句报错诚实得多。
 *
 * <p>{@code serverNow} 是刻意下发的：倒计时必须以服务器时钟为准，否则改本机时间就能白嫖。
 */
@RestController
@RequestMapping("/api/curfew")
public class CurfewController {
    private final CurfewGuard curfew;

    public CurfewController(CurfewGuard curfew) { this.curfew = curfew; }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(HttpServletRequest req) {
        return ApiResponse.ok(curfew.view(AuthContext.uid(req)));
    }
}
