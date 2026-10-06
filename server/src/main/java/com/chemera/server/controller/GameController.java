package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.game.GameService;
import com.chemera.server.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 权威游戏意图 API：客户端仅发意图 + 渲染回执，全部玩法在服务端结算。
 * 需要用户 JWT（游客或正式账号，二者均为 type=user）。见 WebConfig 拦截器注册。
 */
@RestController
@RequestMapping("/api/game")
public class GameController {
    private final GameService game;

    public GameController(GameService game) { this.game = game; }

    /** 整帧快照：载入存档 + 每日刷新落库后返回最新 state。 */
    @GetMapping("/state")
    public ApiResponse<Map<String, Object>> state(HttpServletRequest req) {
        return ApiResponse.ok(game.state(AuthContext.uid(req)));
    }

    /**
     * 通用意图端点：intent 形如 react / bench.place / market.buy / sign …；body 为该意图的参数对象。
     *
     * <p>参数对象里可以带一个保留键 {@code seq}（客户端每次发意图自增一），配合令牌里的 {@code sid}
     * 构成幂等键 {@code (uid, sid, seq)}：弱网重发的那一句不会结算第二遍。老安装包不发这个键，
     * 服务端按"只交付一次"处理，行为与本条落地之前一致。见 {@link com.chemera.server.game.IntentDedupe}。
     */
    @PostMapping("/{intent}")
    public ApiResponse<Map<String, Object>> act(@PathVariable String intent,
                                                @RequestBody(required = false) Map<String, Object> params,
                                                HttpServletRequest req) {
        return ApiResponse.ok(game.act(AuthContext.uid(req), AuthContext.sid(req),
                intent, params == null ? Map.of() : params));
    }
}
