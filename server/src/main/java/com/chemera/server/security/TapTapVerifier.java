package com.chemera.server.security;

import com.chemera.server.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * TapTap 登录票据的验签：把客户端从 TapSDK 拿到的授权（kid + mac_key）换成"这个人是谁"。
 *
 * <p>按 TapTap 的公开文档，服务端不接收 {@code client_secret} 也不直接用 access_token 查询，
 * 而是对一个 GET 请求做 HMAC-SHA1 签名，拼成 MAC 授权头去访问
 * {@code /account/basic-info/v1}，返回体里的 {@code openid} 才是本服账号要绑的主体。
 * 本类把这条链路写成一处，别的地方只认 {@link Identity}。
 *
 * <p>三条硬规矩：
 * <ul>
 *   <li>验不过就是验不过——绝不"猜一个 openId"把人放进游戏。任何异常（超时、非 200、字段缺失、
 *       openid 为空）统一成 401，且不带回显票据内容，免得日志里落下可重放的凭据。</li>
 *   <li>演示票据（{@code dev:}）只在 {@code dev-mode=true} 时认。它是本机回归用的自证通道，
 *       带上它上线等于把登录交给客户端。</li>
 *   <li>没配 {@code client_id} 就直接明确报"未配置"，而不是发一个必然 401 的请求让排查的人猜。</li>
 * </ul>
 */
@Component
public class TapTapVerifier {
    private static final Logger log = LoggerFactory.getLogger(TapTapVerifier.class);

    /** 验签成功后的主体：openId 唯一绑定，name 只当昵称候选（仍要过敏感词）。 */
    public record Identity(String openId, String name) {}

    private static final String PATH = "/account/basic-info/v1";
    private static final int TIMEOUT_MS = 5000;
    /** 昵称候选的上限：app_user.nickname 是 VARCHAR(64)，但游戏内的观感先按 24 字收。 */
    private static final int NICK_MAX = 24;

    private final boolean devMode;
    private final String host;
    private final String configuredClientId;
    private final ObjectMapper om;
    private final HttpClient http;

    public TapTapVerifier(@Value("${chemera.taptap.dev-mode:true}") boolean devMode,
                          @Value("${chemera.taptap.host:open.tapapis.cn}") String host,
                          @Value("${chemera.taptap.client-id:}") String clientId,
                          ObjectMapper om) {
        this.devMode = devMode;
        this.host = host == null || host.isBlank() ? "open.tapapis.cn" : host.trim();
        this.configuredClientId = clientId == null ? "" : clientId.trim();
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build();
    }

    /** 演示通道开着就算就绪：本机回归与浏览器预览不该被"没有 TapTap 应用"卡住。 */
    public boolean ready() {
        return devMode || !configuredClientId.isBlank();
    }

    /** 开发期是否处在自证模式（prod 下 ProdHardening 会拒绝带着它启动）。 */
    public boolean devMode() { return devMode; }

    /**
     * 验证登录票据。
     *
     * @param ticket 客户端上报的 {@code {kid, macKey, clientId}}；{@code devMode} 时也可以是
     *               {@code {dev:"dev:openid:昵称"}} 形态的自证串
     * @return 稳定的第三方主体，永不为 null（失败一律抛 {@link BizException} 401）
     */
    public Identity verify(JsonNode ticket) {
        if (ticket == null || ticket.isNull()) throw BizException.unauthorized("缺少 TapTap 登录票据");
        String dev = text(ticket, "dev");
        if (!dev.isEmpty()) {
            // 自证串只在演示模式认：带它上线等于把"我是谁"交给客户端
            if (!devMode) throw BizException.unauthorized("演示票据已关闭，请使用 TapTap 客户端登录");
            return devIdentity(dev);
        }
        String kid = text(ticket, "kid");
        String macKey = text(ticket, "macKey");
        String clientId = text(ticket, "clientId");
        if (clientId.isEmpty()) clientId = configuredClientId;
        if (kid.isEmpty() || macKey.isEmpty()) throw BizException.unauthorized("TapTap 登录票据不完整");
        if (clientId.isEmpty())
            throw new BizException("TapTap 登录未配置：请通过 CHEMERA_TAPTAP_CLIENT_ID 提供应用 client_id");
        return remote(kid, macKey, clientId);
    }

    /* ---------------- 真实验签 ---------------- */

    private Identity remote(String kid, String macKey, String clientId) {
        long ts = System.currentTimeMillis() / 1000;
        // nonce 每次都要不同（防重放标记），所以取随机串而不是从 kid/ts 派生——后者同一秒内会撞
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String auth = macAuthHeader(kid, macKey, ts, nonce, "GET", PATH, host);
        String url = "https://" + host + PATH + "?client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(TIMEOUT_MS))
                    .header("Authorization", auth).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.info("TapTap 验签返回 HTTP {}（不回显票据）", res.statusCode());
                throw BizException.unauthorized("TapTap 登录验证失败，请重试");
            }
            JsonNode root = om.readTree(res.body());
            JsonNode data = root.has("data") ? root.get("data") : root;
            String openId = text(data, "openid");
            if (openId.isEmpty()) {
                log.info("TapTap 验签响应里没有 openid 字段：可能是接口版本不同，需要按文档核对字段名");
                throw BizException.unauthorized("TapTap 登录验证失败，请重试");
            }
            return new Identity(openId, text(data, "name"));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.info("TapTap 验签调用异常：{}", e.toString());   // 不打 message：里面可能带 URL 查询串
            throw BizException.unauthorized("TapTap 登录暂时不可用，请稍后重试");
        }
    }

    /**
     * 按 TapTap 文档拼 MAC 授权头（纯函数，单测直接打这里核对签名串）。
     *
     * <p>签名输入是七行拼起来再以换行结尾：{@code ts \n nonce \n METHOD \n path \n host \n 443 \n }；
     * 算法是 HMAC-SHA1 后 base64。这里刻意把 {@code ts/nonce} 做成入参而不是内部取当前时间，
     * 否则"签名对不对"这件事永远无法在测试里固定住。
     */
    public static String macAuthHeader(String kid, String macKey, long ts, String nonce,
                                       String method, String path, String host) {
        String input = String.join("\n", String.valueOf(ts), nonce, method.toUpperCase(), path, host, "443", "")
                + "\n";
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(macKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String b64 = Base64.getEncoder().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
            return "MAC id=\"" + kid + "\",ts=\"" + ts + "\",nonce=\"" + nonce + "\",mac=\"" + b64 + "\"";
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 不可用：这台 JVM 连标准算法都没有", e);
        }
    }

    /* ---------------- 演示票据 ---------------- */

    /**
     * 本机回归用的自证串：{@code dev:<openId>[:<昵称>]}。它的意义是让 {@code /api/auth/taptap} 的
     * 建档、绑定、并档三条路径在没有 TapTap 应用的情况下也能被 e2e 跑到——
     * 而 {@code dev-mode=false} 时这里直接关门，保证这套逻辑不会带上线变成后门。
     */
    private static Identity devIdentity(String raw) {
        if (!raw.startsWith("dev:")) throw BizException.unauthorized("演示票据格式应为 dev:<openId>[:<昵称>]");
        String[] p = raw.substring(4).split(":", 2);
        if (p[0].isBlank()) throw BizException.unauthorized("演示票据缺少 openId");
        return new Identity("dev-" + p[0].trim(), p.length > 1 ? p[1].trim() : "");
    }

    private static String text(JsonNode n, String f) {
        if (n == null) return "";
        JsonNode v = n.get(f);
        return v == null || v.isNull() || !v.isTextual() ? "" : v.asText("").trim();
    }

    /**
     * 限流用的票据摘要：把"同一张票据被反复提交"折算成一个定长 key。
     * 只用于进程内计数——不落库、不进日志、不回显，泄露它也不足以重放登录（重放要 mac_key）。
     */
    public static String hint(JsonNode ticket) {
        if (ticket == null) return "";
        String raw = text(ticket, "kid");
        if (raw.isEmpty()) raw = text(ticket, "dev");
        if (raw.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    /** 给健康检查用：一眼看出登录面是真配好了还是靠演示模式在跑。 */
    public String mode() { return devMode ? "dev" : (configuredClientId.isBlank() ? "unconfigured" : "live"); }

    /**
     * 昵称候选的清洗：TapTap 的 name 由玩家自设，本服不能假设它有多长。
     * 刻意的截断而不是拒绝——收不到昵称顶多名字不好看，拒了人家就登不进来了。
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        return s.length() > NICK_MAX ? s.substring(0, NICK_MAX) : s;
    }
}
