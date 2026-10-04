package com.chemera.server.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 上线前的自检闸门：开发配置可以宽容，prod 不行——只要还带着仓库里的默认密钥/默认口令，
 * 就让进程直接拒绝启动，而不是留一个"谁都能登后台"的实例对外服务。
 */
@Component
public class ProdHardening {
    private static final Logger log = LoggerFactory.getLogger(ProdHardening.class);
    private static final String DEV_JWT = "change-me-dev-secret-please-set-env-min-32-chars-long";
    /** 只挡"照抄文档/示例就上线"这一种事故，不替代 A5 的口令强度校验。 */
    private static final Set<String> WEAK_ADMIN = Set.of("admin123", "adminadmin", "changeme", "password", "12345678");

    private final Environment env;
    private final String jwtSecret;
    private final String seedAdminPass;
    private final String corsOrigins;

    public ProdHardening(Environment env,
                         @Value("${chemera.jwt.secret:}") String jwtSecret,
                         @Value("${chemera.seed-admin.password:}") String seedAdminPass,
                         @Value("${chemera.cors.allowed-origins:}") String corsOrigins) {
        this.env = env; this.jwtSecret = jwtSecret; this.seedAdminPass = seedAdminPass; this.corsOrigins = corsOrigins;
    }

    @PostConstruct
    void verify() {
        if (!isProd()) {
            if (jwtSecret.isBlank() || DEV_JWT.equals(jwtSecret))
                log.warn("正在使用仓库内置的开发 JWT 密钥——只可在本机开发使用，上线前用 CHEMERA_JWT_SECRET 覆盖");
            return;
        }
        if (jwtSecret.isBlank() || DEV_JWT.equals(jwtSecret))
            throw new IllegalStateException("prod 启动失败：必须通过环境变量 CHEMERA_JWT_SECRET 提供至少 32 字节的密钥（禁止使用仓库默认值）");
        if (seedAdminPass == null || seedAdminPass.isBlank() || WEAK_ADMIN.contains(seedAdminPass.toLowerCase(Locale.ROOT)))
            throw new IllegalStateException("prod 启动失败：必须经 CHEMERA_SEED_ADMIN_PASS 指定一个非默认的管理员口令，不允许回落到 admin123 这类公开口令");
        if (corsOrigins != null && !corsOrigins.isBlank())
            log.warn("prod 已开放跨域来源：{}（同域部署请清空 CHEMERA_CORS_ORIGINS）", corsOrigins);
    }

    private boolean isProd() {
        return Arrays.stream(env.getActiveProfiles()).anyMatch(p -> "prod".equalsIgnoreCase(p));
    }
}
