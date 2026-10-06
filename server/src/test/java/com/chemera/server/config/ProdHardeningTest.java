package com.chemera.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/** prod 闸门：带着仓库默认密钥/默认口令就必须拒绝启动，开发环境只警告。 */
class ProdHardeningTest {
    private static final String DEV = "change-me-dev-secret-please-set-env-min-32-chars-long";

    private static ProdHardening of(String profiles, String secret, String adminPass, String cors) {
        return of(profiles, secret, adminPass, cors, "", false);
    }

    private static ProdHardening of(String profiles, String secret, String adminPass, String cors,
                                    String adKey, boolean adDevMode) {
        return of(profiles, secret, adminPass, cors, adKey, adDevMode, false);
    }

    private static ProdHardening of(String profiles, String secret, String adminPass, String cors,
                                    String adKey, boolean adDevMode, boolean taptapDevMode) {
        MockEnvironment env = new MockEnvironment();
        if (!profiles.isBlank()) env.setActiveProfiles(profiles.split(","));
        return new ProdHardening(env, secret, adminPass, cors, adKey, adDevMode, taptapDevMode);
    }

    @Test
    void prodRefusesMissingSecret() {
        assertThrows(IllegalStateException.class, () -> of("mysql,prod", "", "S0meGoodPass", "").verify());
    }

    @Test
    void prodRefusesRepoDefaultSecret() {
        assertThrows(IllegalStateException.class, () -> of("mysql,prod", DEV, "S0meGoodPass", "").verify());
    }

    @Test
    void prodRefusesDefaultAdminBootstrap() {
        assertThrows(IllegalStateException.class, () -> of("mysql,prod", "a-real-prod-secret-value-32-bytes!!", "", "").verify());
    }

    /** 只填了非空还不够：照抄 README 示例里的 admin123 上线，等于把后台钥匙插在门上。 */
    @Test
    void prodRefusesPubliclyKnownAdminPassword() {
        assertThrows(IllegalStateException.class,
                () -> of("mysql,prod", "a-real-prod-secret-value-32-bytes!!", "admin123", "").verify());
        assertThrows(IllegalStateException.class,
                () -> of("mysql,prod", "a-real-prod-secret-value-32-bytes!!", "ChangeMe", "").verify());
    }

    @Test
    void prodAcceptsHardenedConfig() {
        assertDoesNotThrow(() -> of("mysql,prod", "a-real-prod-secret-value-32-bytes!!", "Operator-Pass-2026", "").verify());
    }

    @Test
    void devOnlyWarns() {
        assertDoesNotThrow(() -> of("mysql", DEV, "", "*").verify());
    }

    /** 激励视频的自证通道等于"任何客户端都能白拿奖励"，与付费面下线的初衷直接冲突，prod 一律拒起。 */
    @Test
    void prodRefusesAdDevMode() {
        assertThrows(IllegalStateException.class,
                () -> of("mysql,prod", "a-real-prod-secret-value-32-bytes!!", "Operator-Pass-2026", "", "", true).verify());
    }

    /** 口令缺失只警告不拒起：广告中心报未就绪、不签发工单，比让整个服务起不来更可用。 */
    @Test
    void prodWarnsButSurvivesWithoutAdKey() {
        assertDoesNotThrow(() -> of("mysql,prod", "a-real-prod-secret-value-32-bytes!!", "Operator-Pass-2026", "", "", false).verify());
        assertDoesNotThrow(() -> of("mysql,prod", "a-real-prod-secret-value-32-bytes!!", "Operator-Pass-2026", "", "a-real-ad-security-key", false).verify());
    }

    /**
     * TapTap 的自证票据比广告那条更致命：它回答的是"你是谁"，开着它任何人都能递一句
     * dev:&lt;别人的 openid&gt; 登进别人的账号。prod 必须直接拒绝启动，而不是等运营记得去关。
     */
    @Test
    void prodRefusesTapTapDevMode() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> of("mysql,prod", "a-real-prod-secret-value-32-bytes!!", "Operator-Pass-2026", "", "", false, true).verify());
        assertTrue(e.getMessage().contains("taptap.dev-mode"), e.getMessage());
    }

    /** 开发环境照旧只警告：本机回归要靠这条自证通道跑通建档/绑定/并档三条路径——但必须显式挂 dev。 */
    @Test
    void devProfileSurvivesDemoChannels() {
        assertDoesNotThrow(() -> of("mysql,dev", DEV, "", "", "", false, true).verify());
        assertDoesNotThrow(() -> of("mysql,dev", DEV, "", "", "any-ad-key", true, true).verify());
    }

    /**
     * F5 的正主：漏掉 profile 是最现实的上线事故。仓库配置现在默认 dev-mode=false，
     * 但只要有人把 {@code CHEMERA_AD_DEV_MODE=true} 留在 .env 里、启动时用裸 {@code mysql}，
     * 公网实例就同时开着"客户端自证看完广告"与"dev: 票据换账号"两条通道，而且一句警告都没有。
     * 现在它直接起不来。
     */
    @Test
    void demoChannelWithoutDevProfileRefusesToStart() {
        IllegalStateException ad = assertThrows(IllegalStateException.class,
                () -> of("mysql", "a-real-secret-value-for-dev-profile-test!!", "Operator-Pass-2026", "", "", true, false).verify());
        assertTrue(ad.getMessage().contains("dev-mode"), ad.getMessage());
        assertTrue(ad.getMessage().contains("ad"), ad.getMessage());

        IllegalStateException tap = assertThrows(IllegalStateException.class,
                () -> of("mysql", "a-real-secret-value-for-dev-profile-test!!", "Operator-Pass-2026", "", "", false, true).verify());
        assertTrue(tap.getMessage().contains("taptap"), tap.getMessage());

        // 两个都开时点名 ad/taptap，并且没有 profile 也算"没有 dev"
        IllegalStateException both = assertThrows(IllegalStateException.class,
                () -> of("", "a-real-secret-value-for-dev-profile-test!!", "Operator-Pass-2026", "", "", true, true).verify());
        assertTrue(both.getMessage().contains("ad/taptap"), both.getMessage());
    }

    /** 裸 mysql 且演示通道关着是合法形态（README 的最低配置），不该被新闸门误伤。 */
    @Test
    void bareMysqlWithoutDemoChannelsStarts() {
        assertDoesNotThrow(() -> of("mysql", "a-real-secret-value-for-dev-profile-test!!", "Operator-Pass-2026", "", "any-ad-key", false, false).verify());
    }
}
