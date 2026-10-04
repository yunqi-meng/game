package com.chemera.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/** prod 闸门：带着仓库默认密钥/默认口令就必须拒绝启动，开发环境只警告。 */
class ProdHardeningTest {
    private static final String DEV = "change-me-dev-secret-please-set-env-min-32-chars-long";

    private static ProdHardening of(String profiles, String secret, String adminPass, String cors) {
        MockEnvironment env = new MockEnvironment();
        if (!profiles.isBlank()) env.setActiveProfiles(profiles.split(","));
        return new ProdHardening(env, secret, adminPass, cors);
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
}
