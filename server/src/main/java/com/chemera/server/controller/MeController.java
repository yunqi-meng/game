package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.entity.AppUser;
import com.chemera.server.game.CurfewGuard;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.security.AuthContext;
import com.chemera.server.service.SaveService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class MeController {
    private final UserMapper users;
    private final SaveService saves;
    private final CurfewGuard curfew;
    public MeController(UserMapper users, SaveService saves, CurfewGuard curfew) {
        this.users = users; this.saves = saves; this.curfew = curfew;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> me(HttpServletRequest req) {
        long uid = AuthContext.uid(req);
        AppUser u = users.findById(uid);
        Map<String, Object> m = new LinkedHashMap<>();
        if (u != null) {
            m.put("user", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("guest", u.getIsGuest() != null && u.getIsGuest() == 1);
        }
        m.putAll(saves.get(uid));
        return ApiResponse.ok(m);
    }

    /**
     * 玩家自助开关青少年模式。没有实名认证接口可用（那是独立资质），所以"是否未成年人"
     * 只有两个来源：玩家自己在这里打开，或运营在后台【用户管理】标记——两边写的都是同一列。
     *
     * <p>这里只接受 {@code on=true/false}，不允许玩家把已经生效的限制"关掉后又改成别的值"这类
     * 花活：写死成布尔，才不会出现"客户端传了个字符串 0 被判成真"的偏差。
     * 关掉青少年模式是允许的（它是家长监护工具，不是账号处罚），但每次开关都立即失效缓存，
     * 让玩家点完就见到结果，而不是等 60 秒。
     */
    @PostMapping("/minor")
    public ApiResponse<Map<String, Object>> minor(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        long uid = AuthContext.uid(req);
        boolean on = Boolean.TRUE.equals(b.get("on")) || "true".equalsIgnoreCase(String.valueOf(b.get("on")));
        users.setMinor(uid, on ? 1 : 0);
        curfew.invalidate(uid);
        // 直接回权威视图：其中 minor 是作废缓存后重新查库的结果，比回显玩家刚点的那个值可信
        return ApiResponse.ok(curfew.view(uid));
    }
}

