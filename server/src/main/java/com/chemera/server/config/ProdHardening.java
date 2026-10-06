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
 *
 * <p>演示通道（广告自证、TapTap 自证票据）走的是另一条口径：<b>默认关</b>，要开得显式挂 {@code dev} profile。
 * 于是这里判的是"开着它却没有 dev profile"——不管 prod 还是裸 mysql，一律拒起（见 {@link #verify()} 里那段注释）。
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
    private final String adSecurityKey;
    private final boolean adDevMode;
    private final boolean taptapDevMode;

    public ProdHardening(Environment env,
                         @Value("${chemera.jwt.secret:}") String jwtSecret,
                         @Value("${chemera.seed-admin.password:}") String seedAdminPass,
                         @Value("${chemera.cors.allowed-origins:}") String corsOrigins,
                         @Value("${chemera.ad.security-key:}") String adSecurityKey,
                         @Value("${chemera.ad.dev-mode:false}") boolean adDevMode,
                         @Value("${chemera.taptap.dev-mode:false}") boolean taptapDevMode) {
        this.env = env; this.jwtSecret = jwtSecret; this.seedAdminPass = seedAdminPass; this.corsOrigins = corsOrigins;
        this.adSecurityKey = adSecurityKey == null ? "" : adSecurityKey.trim();
        this.adDevMode = adDevMode;
        this.taptapDevMode = taptapDevMode;
    }

    @PostConstruct
    void verify() {
        if (!isProd()) {
            // 演示通道只认两种合法形态：prod 下必须关（下面那段），非 prod 下必须显式挂着 dev profile。
            // 单靠"prod 拒绝"不够——README 教的是 mysql,prod，可 .env.example 与裸 java -jar 的默认都是 mysql，
            // 漏掉那个 profile 的实例就同时开着"客户端自证看完广告"和"dev: 票据换账号"两条通道，而且没人会报错。
            if ((adDevMode || taptapDevMode) && !isDevProfile())
                throw new IllegalStateException("启动失败：chemera." + demoChannelNames() + ".dev-mode 处于开启状态，"
                        + "但当前 profile 既不是 prod 也没有 dev（active=" + activeProfiles() + "）。"
                        + "本机开发请显式带 dev（SPRING_PROFILES_ACTIVE=mysql,dev，server/run.sh 与 run.bat 会自动补上），"
                        + "对外部署请把 CHEMERA_AD_DEV_MODE / CHEMERA_TAPTAP_DEV_MODE 置 false（现在的应用默认就是 false，"
                        + "会红说明是有人手动打开了它）");
            if (jwtSecret.isBlank() || DEV_JWT.equals(jwtSecret))
                log.warn("正在使用仓库内置的开发 JWT 密钥——只可在本机开发使用，上线前用 CHEMERA_JWT_SECRET 覆盖");
            if (adDevMode)
                log.warn("激励视频处于演示模式（dev profile）：客户端可凭工单自证发放，仅用于本机回归与浏览器预览");
            if (taptapDevMode)
                log.warn("TapTap 登录处于演示模式（dev profile）：dev: 自证票据可直接换账号，仅用于本机回归与浏览器预览");
            return;
        }
        if (jwtSecret.isBlank() || DEV_JWT.equals(jwtSecret))
            throw new IllegalStateException("prod 启动失败：必须通过环境变量 CHEMERA_JWT_SECRET 提供至少 32 字节的密钥（禁止使用仓库默认值）");
        if (seedAdminPass == null || seedAdminPass.isBlank() || WEAK_ADMIN.contains(seedAdminPass.toLowerCase(Locale.ROOT)))
            throw new IllegalStateException("prod 启动失败：必须经 CHEMERA_SEED_ADMIN_PASS 指定一个非默认的管理员口令，不允许回落到 admin123 这类公开口令");
        // 这是本作的发钱总闸：演示模式下任何客户端都能白拿奖励，绝不能带它上线
        if (adDevMode)
            throw new IllegalStateException("prod 启动失败：chemera.ad.dev-mode 必须为 false（广告奖励的自证通道等于把激励视频变成无限提款机）");
        // 这条比广告更致命：自证票据等于"我是谁"由客户端说了算，任何人都能登进别人的账号
        if (taptapDevMode)
            throw new IllegalStateException("prod 启动失败：chemera.taptap.dev-mode 必须为 false"
                    + "（TapTap 登录的自证票据等于把账号身份交给客户端，是登录面的后门）");
        if (adSecurityKey.isBlank())
            log.warn("prod 未配置 CHEMERA_AD_SECURITY_KEY：激励视频不会签发工单，广告位对玩家不可用（/api/healthz 里 ad.ready=false）");
        if (corsOrigins != null && !corsOrigins.isBlank())
            log.warn("prod 已开放跨域来源：{}（同域部署请清空 CHEMERA_CORS_ORIGINS）", corsOrigins);
    }

    private boolean isProd() {
        return hasProfile("prod");
    }

    /** 演示通道的唯一合法入口：显式的 dev profile（application-dev.yml 里才把两个 dev-mode 打开）。 */
    private boolean isDevProfile() {
        return hasProfile("dev");
    }

    private boolean hasProfile(String name) {
        return Arrays.stream(env.getActiveProfiles()).anyMatch(p -> name.equalsIgnoreCase(p.trim()));
    }

    private String activeProfiles() {
        String s = String.join(",", env.getActiveProfiles());
        return s.isBlank() ? "(无)" : s;
    }

    /** 报错里点名是哪个通道，运营才知道该去关哪一个。 */
    private String demoChannelNames() {
        if (adDevMode && taptapDevMode) return "ad/taptap";
        return adDevMode ? "ad" : "taptap";
    }
}
