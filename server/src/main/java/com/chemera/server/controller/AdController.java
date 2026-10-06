package com.chemera.server.controller;

import com.chemera.server.game.AdService;
import com.chemera.server.security.RateGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 激励视频的服务器回调（SSV）：TapADN / Dirichlet SSP 播完广告后来这里，是奖励唯一的发放入口。
 *
 * <p>这条路径<b>不带用户 JWT</b>——来访的是广告网络的服务器，所以身份完全靠签名（{@link AdService#callback}）。
 * 它刻意不挂在 {@code /api/game/**} 下，避免被 AuthInterceptor 挡成 401。
 *
 * <p>正因为没有 JWT，它此前是全站唯一"谁都能打、每打一次都要算一次哈希查一次库"的公开写路径，
 * 既没配额也没来源限制。这一轮补两道（都不替代验签）：
 * <ol>
 *   <li>{@link RateGuard#checkAdCallback} 按来源 IP 限频次，超了回 429——平台会重试，工单还是 issued，
 *       配额恢复后奖励照发，所以防洪不会吞奖；</li>
 *   <li>{@code chemera.ad.callback-allow-ips} 白名单（逗号分隔，条目以 {@code .} 或 {@code *} 结尾按前缀匹配）。
 *       留空即不限制：平台没公布固定网段时，宁可只靠配额，也不要配一条把线上奖励全挡死的规则。</li>
 * </ol>
 *
 * <p>回调参数用 query 还是 form、回执要 JSON 还是纯文本，各家 ADN 不一样，这里两种都接：
 * query/form 与 JSON 请求体会合并；{@code chemera.ad.callback-ack=json|text} 切换回执形态。
 */
@RestController
@RequestMapping("/api/ad")
public class AdController {

    private static final Logger log = LoggerFactory.getLogger(AdController.class);

    private final AdService ads;
    private final ObjectMapper om;
    private final String ackMode;
    private final RateGuard guard;
    private final List<String> allowIps;

    public AdController(AdService ads, ObjectMapper om, RateGuard guard,
                        @Value("${chemera.ad.callback-ack:json}") String ackMode,
                        @Value("${chemera.ad.callback-allow-ips:}") String allowIps) {
        this.ads = ads; this.om = om; this.guard = guard;
        this.ackMode = ackMode == null ? "json" : ackMode.trim().toLowerCase();
        this.allowIps = parseAllowIps(allowIps);
    }

    @PostMapping("/callback")
    public ResponseEntity<?> reward(@RequestParam(required = false) Map<String, String> form,
                                    @RequestBody(required = false) String raw,
                                    HttpServletRequest req) {
        return handle(merge(form, raw), req);
    }

    @GetMapping("/callback")
    public ResponseEntity<?> rewardGet(@RequestParam Map<String, String> q, HttpServletRequest req) {
        return handle(merge(q, null), req);
    }

    private ResponseEntity<?> handle(Map<String, String> p, HttpServletRequest req) {
        String ip = guard.ipOf(req);
        // 先扣配额再验签：验签是这条路径上唯一"贵"的一步（哈希 + 两次查询），挡在它前面的才有意义
        guard.checkAdCallback(ip);
        if (!allowed(ip)) {
            log.warn("广告回调来源不在白名单，已拒 ip={} trans={} extra 摘要={}",
                    ip, p.getOrDefault("trans_id", ""), digest(p.get("extra")));
            return ack(Map.of("ok", false, "code", "ip", "msg", "来源不在白名单"));
        }
        return ack(ads.callback(p));
    }

    /** 按 {@code callback-ack} 决定回 JSON 还是纯文本；参数不完整也要有回执，否则平台会当成投递失败一直重试。 */
    private ResponseEntity<?> ack(Map<String, Object> r) {
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        if (!ok) log.info("广告回调未受理 code={} msg={}", r.get("code"), r.get("msg"));
        if ("text".equals(ackMode))
            return ResponseEntity.ok(ok ? "success" : "fail");
        // Dirichlet 侧文档没写死回执格式；若平台要求特定字段，改 callback-ack 或在反代处转换即可
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ok ? 0 : 1);
        body.put("msg", ok ? "success" : String.valueOf(r.getOrDefault("msg", "fail")));
        return ResponseEntity.ok(body);
    }

    private boolean allowed(String ip) {
        if (allowIps.isEmpty()) return true;
        String peer = ip == null ? "" : ip.trim();
        for (String a : allowIps) {
            if (a.endsWith("*")) {
                if (peer.startsWith(a.substring(0, a.length() - 1))) return true;
            } else if (a.endsWith(".")) {
                if (peer.startsWith(a)) return true;
            } else if (peer.equals(a)) return true;
        }
        return false;
    }

    /** 白名单条目归一：去空白、把裸前缀（如 {@code 203.0.113.}）留着，其它一律小写比较。 */
    private static List<String> parseAllowIps(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null)
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
        return List.copyOf(out);
    }

    /** 工单号只打摘要：它本身是随机凭证，进日志等于把它抄进了人人可读的文件。 */
    private static String digest(String ticket) {
        String t = ticket == null ? "" : ticket.trim();
        return t.length() <= 6 ? "(短)" : t.substring(0, 6) + "…(" + t.length() + ")";
    }

    /** query/form 与 JSON 请求体都接：平台用哪种方式回传都能认。 */
    private Map<String, String> merge(Map<String, String> params, String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params != null) params.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        if (raw != null && raw.trim().startsWith("{")) {
            try {
                JsonNode n = om.readTree(raw);
                n.fields().forEachRemaining(e -> {
                    if (e.getValue().isValueNode()) out.putIfAbsent(e.getKey(), e.getValue().asText());
                });
            } catch (Exception e) {
                log.debug("广告回调 JSON 体解析失败，按 query/form 参数处理", e);
            }
        }
        return out;
    }
}
