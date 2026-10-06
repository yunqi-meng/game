package com.chemera.server.config;

import com.chemera.server.security.AdminInterceptor;
import com.chemera.server.security.AuthInterceptor;
import com.chemera.server.security.RequestTraceInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final RequestTraceInterceptor traceInterceptor;
    /** 意图耗时与 SQL 条数的记账拦截器（G8），只挂在 {@code /api/game/**} 上。 */
    private final com.chemera.server.stats.IntentTraceInterceptor intentTrace;
    /** 空列表=不下发任何跨域许可（生产默认）；开发期热调前端时再列来源。 */
    private final List<String> corsOrigins;

    public WebConfig(AuthInterceptor a, AdminInterceptor b, RequestTraceInterceptor t,
                     com.chemera.server.stats.IntentTraceInterceptor intentTrace,
                     @Value("${chemera.cors.allowed-origins:}") List<String> corsOrigins) {
        this.authInterceptor = a; this.adminInterceptor = b; this.traceInterceptor = t;
        this.intentTrace = intentTrace;
        this.corsOrigins = corsOrigins;
    }

    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${chemera.security.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry reg) {
        // 静态目录索引：管理后台 SPA（hash 路由，服务端只需回 index.html）与本地同域的 H5 客户端
        reg.addViewController("/").setViewName("forward:/index.html");
        reg.addViewController("/admin").setViewName("forward:/admin/index.html");
        reg.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry reg) {
        // 追踪拦截器最先注册：后续鉴权抛异常时它的 afterCompletion 仍会跑，401/429 也留得下日志
        reg.addInterceptor(traceInterceptor).addPathPatterns("/**");
        // 意图统计紧跟其后：它要在鉴权之前 begin、在响应之后 end，才知道"这一句意图花了几天 SQL"
        reg.addInterceptor(intentTrace).addPathPatterns("/api/game/**");
        reg.addInterceptor(authInterceptor)
                .addPathPatterns("/api/me", "/api/me/minor", "/api/curfew/status", "/api/analytics/event",
                        "/api/game/**", "/api/report",
                        "/api/auth/pass", "/api/auth/delete", "/api/auth/upgrade");
        reg.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/api/**")
                .excludePathPatterns("/admin/api/login", "/admin/api/ping");
    }

    /**
     * 客户端资源全部以 ?v=N 手工打版、后台资源由 Vite 内容哈希命名，因此都能放心长缓存；
     * 换版本靠 index.html 里的查询串（入口文档本身不缓存，见 RequestTraceInterceptor）。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry reg) {
        CacheControl immutable = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
        reg.addResourceHandler("/js/**").addResourceLocations("classpath:/static/js/").setCacheControl(immutable);
        reg.addResourceHandler("/css/**").addResourceLocations("classpath:/static/css/").setCacheControl(immutable);
        reg.addResourceHandler("/images/**").addResourceLocations("classpath:/static/images/").setCacheControl(immutable);
        reg.addResourceHandler("/admin/assets/**")
                .addResourceLocations("classpath:/static/admin/assets/").setCacheControl(immutable);
    }

    @Override
    public void addCorsMappings(CorsRegistry reg) {
        if (corsOrigins.isEmpty() || corsOrigins.stream().allMatch(String::isBlank)) return;
        reg.addMapping("/**")
                .allowedOriginPatterns(corsOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
