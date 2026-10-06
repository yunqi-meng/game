package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.game.Content;
import com.chemera.server.game.ContentRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本门（公开接口，不带鉴权）：安卓壳启动时先问一次"服务端要求的最低包版本号是多少"。
 *
 * <p>为什么放在鉴权之前：强制升级的意义正是"旧包连门都进不来"，如果这个接口要 token，
 * 旧包就已经拿不到 token 了，成了死循环。所以它必须匿名可调，也因此只下发四个展示字段，
 * 不碰存档、不碰内容。
 *
 * <p>判定发生在壳里而不是服务器代判：客户端才知道自己装的是第几版（{@code BuildConfig}），
 * 服务器只知道规则。服务器把规则说清楚，壳比对完自己决定弹窗还是放行——
 * 这条链路管不了破解包，它的受众是"拿着半年前的包还在点"的普通玩家。
 */
@RestController
@RequestMapping("/api/app")
public class AppVersionController {
    private final ContentRegistry reg;

    public AppVersionController(ContentRegistry reg) { this.reg = reg; }

    @GetMapping("/version")
    public ApiResponse<Map<String, Object>> version() {
        Content.AppVersion v = reg.current().config.appVersionOr();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minBuild", v.minOr());
        m.put("latestBuild", v.latestOr());
        m.put("note", v.note() == null ? "" : v.note());
        m.put("url", v.url() == null ? "" : v.url());
        // 内容版本号顺带带出：壳里的"检查更新"能一并告诉玩家服务端内容有没有换过
        m.put("contentVersion", reg.current().version);
        return ApiResponse.ok(m);
    }
}
