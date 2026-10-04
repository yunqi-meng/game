package com.chemera.server.config;

import com.chemera.server.entity.AdminUser;
import com.chemera.server.mapper.AdminMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 启动时确保存在一个超级管理员（幂等）。 */
@Configuration
public class AdminInitializer {
    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    @Bean
    ApplicationRunner seedAdmin(AdminMapper admins, PasswordEncoder enc,
                                @Value("${chemera.seed-admin.username:admin}") String user,
                                @Value("${chemera.seed-admin.password:}") String pass) {
        return args -> {
            long n = admins.count();
            if (n == 0) {
                boolean fallback = pass == null || pass.isBlank();
                String p = fallback ? "admin123" : pass;
                AdminUser a = new AdminUser();
                a.setUsername(user); a.setPassHash(enc.encode(p)); a.setRole("super");
                // 回落弱默认时必须强制首次改密，否则后台等于长期挂着公开口令
                a.setMustChangePassword(fallback ? 1 : 0);
                admins.insert(a);
                log.warn("已创建默认超级管理员：username={} password={}（首次登录后请立即修改，并通过 CHEMERA_SEED_ADMIN_PASS 设置正式口令）",
                        user, p);
            } else if (pass != null && !pass.isBlank()) {
                AdminUser ex = admins.findByUsername(user);
                if (ex == null) {
                    AdminUser a = new AdminUser();
                    a.setUsername(user); a.setPassHash(enc.encode(pass)); a.setRole("super");
                    a.setMustChangePassword(0);
                    admins.insert(a);
                    log.info("已创建超级管理员：{}", user);
                }
            }
        };
    }
}
