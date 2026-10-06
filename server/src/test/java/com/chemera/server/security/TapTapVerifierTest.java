package com.chemera.server.security;

import com.chemera.server.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TapTap 登录票据的验签面。
 *
 * <p>真实那次出网调用需要应用密钥与网络，本机跑不了，所以这里覆盖的是"能自己算清楚的全部"：
 * MAC 授权头的签名与格式、演示票据的门、缺配置时的措辞、限流摘要的稳定性。
 * 唯一没被覆盖的是上游到底返不返回 openid——那一条留给对接时的 e2e 手工核对，
 * 并把字段缺失的分支也写成 401，宁可让玩家重试也不放行。
 */
class TapTapVerifierTest {
    private static final ObjectMapper OM = new ObjectMapper();

    private static TapTapVerifier verifier(boolean devMode, String clientId) {
        return new TapTapVerifier(devMode, "open.tapapis.cn", clientId, OM);
    }

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 固定输入下的签名必须与一个独立实现逐字节相同，否则真机对接时"验不过"根本无从排查。
     * 重算命令（bash）：
     * <pre>
     * printf '1700000000\nabcdef0123456789\nGET\n/account/basic-info/v1\nopen.tapapis.cn\n443\n\n' \
     *   | openssl dgst -sha1 -hmac 'tap-test-mac-secret' -binary | base64
     * </pre>
     */
    @Test
    void macHeaderMatchesAnIndependentImplementation() {
        String h = TapTapVerifier.macAuthHeader("tap.test.keyid", "tap-test-mac-secret",
                1700000000L, "abcdef0123456789", "GET", "/account/basic-info/v1", "open.tapapis.cn");
        assertEquals("MAC id=\"tap.test.keyid\",ts=\"1700000000\",nonce=\"abcdef0123456789\","
                + "mac=\"cm/rj55g35dEKHK3aZaQCUWltxQ=\"", h);
    }

    /** 签名要覆盖每一个字段：换了任何一个都得得到不同的 mac，否则等于没签。 */
    @Test
    void everySigningFieldChangesTheMac() {
        String base = TapTapVerifier.macAuthHeader("kid", "secret", 1L, "nonce", "GET", "/p", "h");
        assertNotEquals(base, TapTapVerifier.macAuthHeader("kid", "secret", 2L, "nonce", "GET", "/p", "h"));
        assertNotEquals(base, TapTapVerifier.macAuthHeader("kid", "secret", 1L, "nunce", "GET", "/p", "h"));
        assertNotEquals(base, TapTapVerifier.macAuthHeader("kid", "othe", 1L, "nonce", "GET", "/p", "h"));
        assertNotEquals(base, TapTapVerifier.macAuthHeader("kid", "secret", 1L, "nonce", "POST", "/p", "h"));
        assertNotEquals(base, TapTapVerifier.macAuthHeader("kid", "secret", 1L, "nonce", "GET", "/q", "h"));
        assertNotEquals(base, TapTapVerifier.macAuthHeader("kid", "secret", 1L, "nonce", "GET", "/p", "i"));
    }

    /** 演示票据就是"客户端自报身份"，只允许在 dev-mode 下存在。 */
    @Test
    void devTicketIsOnlyHonouredWhileDevModeIsOn() {
        TapTapVerifier.Identity id = verifier(true, "").verify(json("{\"dev\":\"dev:alice:小艾\"}"));
        assertEquals("dev-alice", id.openId());
        assertEquals("小艾", id.name());

        BizException e = assertThrows(BizException.class, () ->
                verifier(false, "a-real-client-id").verify(json("{\"dev\":\"dev:alice:小艾\"}")));
        assertEquals(401, e.code);
        assertTrue(e.getMessage().contains("演示票据已关闭"), e.getMessage());
    }

    @Test
    void devTicketStillNeedsAnOpenId() {
        assertEquals(401, assertThrows(BizException.class,
                () -> verifier(true, "").verify(json("{\"dev\":\"dev:\"}"))).code);
        // 少了 dev: 前缀的自证串不算身份，也不会被当成 openid 放过去
        assertEquals(401, assertThrows(BizException.class,
                () -> verifier(true, "").verify(json("{\"dev\":\"alice\"}"))).code);
    }

    @Test
    void missingTicketIsUnauthorized() {
        assertEquals(401, assertThrows(BizException.class, () -> verifier(true, "").verify(null)).code);
        assertEquals(401, assertThrows(BizException.class, () -> verifier(true, "").verify(json("{}"))).code);
    }

    /** 没配 client_id 要直接说"未配置"，而不是发一个必然失败的出网请求让人猜。 */
    @Test
    void liveModeWithoutClientIdSaysUnconfigured() {
        BizException e = assertThrows(BizException.class, () ->
                verifier(false, "").verify(json("{\"kid\":\"k\",\"macKey\":\"m\"}")));
        assertTrue(e.getMessage().contains("CHEMERA_TAPTAP_CLIENT_ID"), e.getMessage());
    }

    /** 票据缺一半就到此为止：绝不带着半张票据去敲上游（那是一次出网调用）。 */
    @Test
    void incompleteTicketFailsBeforeAnyNetworkCall() {
        BizException e = assertThrows(BizException.class, () ->
                verifier(false, "some-client-id").verify(json("{\"kid\":\"only-kid\"}")));
        assertEquals(401, e.code);
        assertTrue(e.getMessage().contains("不完整"), e.getMessage());
    }

    @Test
    void modeAndReadinessTellOperatorsWhichKindOfLoginIsLive() {
        assertEquals("dev", verifier(true, "").mode());
        assertEquals("unconfigured", verifier(false, "").mode());
        assertEquals("live", verifier(false, "client-id").mode());
        assertTrue(verifier(false, "client-id").ready());
        assertFalse(verifier(false, "   ").ready());
        assertTrue(verifier(true, "").ready(), "演示模式得让本机回归继续跑");
    }

    @Test
    void hintIsStableShortAndCarriesNoSecret() {
        String h = TapTapVerifier.hint(json("{\"kid\":\"sensitive-kid\",\"macKey\":\"super-secret-key\"}"));
        assertEquals(16, h.length());
        assertEquals(h, TapTapVerifier.hint(json("{\"kid\":\"sensitive-kid\"}")), "同一张票据必须落到同一个计数 key");
        assertFalse(h.contains("secret"));
        assertFalse(h.contains("sensitive"));
        assertEquals("", TapTapVerifier.hint(null));
        assertEquals("", TapTapVerifier.hint(json("{}")), "空票据不占计数 key");
    }

    /** 昵称只是候选：太长就截，绝不因为名字不好看把人挡在登录外。 */
    @Test
    void longNicknamesAreTrimmedInsteadOfRejected() {
        assertEquals("", TapTapVerifier.sanitizeName(null));
        assertEquals("abc", TapTapVerifier.sanitizeName("  abc  "));
        assertEquals(24, TapTapVerifier.sanitizeName("名".repeat(40)).length());
        assertEquals("短名字", TapTapVerifier.sanitizeName("短名字"));
    }
}
