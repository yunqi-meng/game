package com.chemera.server.web;

import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.admin.AdminContentController;
import com.chemera.server.controller.admin.AdminSupport;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.game.ContentRegistry;
import com.chemera.server.mapper.AdminMapper;
import com.chemera.server.mapper.ConfigMapper;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.ContentRevisionMapper;
import com.chemera.server.security.AdminInterceptor;
import com.chemera.server.security.JwtService;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.ContentHealthService;
import com.chemera.server.service.ContentRevisionService;
import com.chemera.server.service.ContentService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code GET /admin/api/content/version} 这一格数字在 HTTP 层的长相。
 *
 * <p>它存在的理由很窄：后台【内容】的体检卡片只要<b>一个</b>数字，以前它为这一个数字去拉整份
 * {@code /dashboard/overview}（十几条聚合 SQL）。所以这一层要钉的就是"窄"本身——
 * 回执里除 {@code contentVersion} 不许有第二个键，而且<b>一条 SQL 都不许多问</b>
 * （桩故意把 {@code allEnabled()} 摆着没填：只要实现顺手去读整包，这里当场多出一条调用）。
 *
 * <p>转换器装的是线上那一份（{@code non_null} + ISO 日期）：这一层断言的是浏览器真正读到的那串字，
 * 键名拼错、多带一个字段，面板都是在那一串上读出来的，不是在 Java 对象上。
 *
 * <p>权限用的是本控制器其余读接口那一条：{@code AdminInterceptor} 认角色、读不挡 viewer（写才 {@code requireWriter}），
 * 所以这里跑的是<b>真</b>拦截器 + 库里那份角色，而不是给控制器塞一个假用户。
 */
class AdminContentVersionHttpTest {

    private ContentMapper content;
    private ConfigMapper config;
    private AdminMapper admins;
    private ContentService contentService;
    private JwtService jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        content = mock(ContentMapper.class);
        config = mock(ConfigMapper.class);
        admins = mock(AdminMapper.class);
        ObjectMapper om = new ObjectMapper();
        // 真的 ContentService（只假 mapper）：这一趟要走的是"版本号从哪来"这条链，
        // 桩在 service 上就等于把被测的那一段自己替掉了。
        contentService = new ContentService(content, config, om, 1000L);
        AdminSupport support = new AdminSupport(content, contentService);
        ContentRevisionService revisions = new ContentRevisionService(mock(ContentRevisionMapper.class), content);
        ContentRegistry registry = mock(ContentRegistry.class);
        jwt = new JwtService("content-version-http-layer-secret!!", 60);

        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminContentController(content, support, mock(AuditService.class), om, registry,
                                contentService, revisions,
                                new ContentHealthService(content, registry, om)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(), wireConverter())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();

        when(content.version()).thenReturn(27L);
        when(content.allEnabled()).thenReturn(List.of());
        when(config.allRaw()).thenReturn(List.of());
    }

    /** 线上出参形状（见 {@code AdminOptimisticLockHttpTest.wireConverter} 的同一段说明）。 */
    private static MappingJackson2HttpMessageConverter wireConverter() {
        ObjectMapper wire = Jackson2ObjectMapperBuilder.json()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return new MappingJackson2HttpMessageConverter(wire);
    }

    /** 库里躺着的角色决定能不能进；令牌里写什么都没用。 */
    private String asAdmin(long id, String roleInDb) {
        return tokenFor(row(id, roleInDb, 0, 0));
    }

    private String tokenFor(AdminUser a) {
        when(admins.findById(a.getId())).thenReturn(a);
        // 令牌里一律声明 super：验证裁决看的是库里的角色与状态，不是令牌里那一份
        return jwt.issue(a.getId(), "admin", "super", a.getUsername());
    }

    private static AdminUser row(long id, String role, int status, int mustChangePassword) {
        AdminUser a = new AdminUser();
        a.setId(id); a.setUsername("ops"); a.setRole(role); a.setStatus(status);
        a.setMustChangePassword(mustChangePassword); a.setPassHash("h");
        return a;
    }

    /* ---------------- 形状：只要一个数，别多 ---------------- */

    /**
     * 回执只有 {@code contentVersion} 这一个键，值是库里那个数。
     *
     * <p>{@code length()==1} 是这个接口唯一的"契约"：一旦有人顺手把整包计数、时间戳塞进来，
     * 面板那边读的还是同一个键，但"为了一格数字去拉整包"这件事就换个地方复活了。
     */
    @Test
    void theOnlyThingThisEndpointSaysIsTheVersion() throws Exception {
        mvc.perform(get("/admin/api/content/version")
                        .header("Authorization", "Bearer " + asAdmin(2, "editor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data.contentVersion").value(27));
    }

    /**
     * 问一个版本号不许顺带读整包。
     *
     * <p>{@code bundle()} 会把全部启用行拉进 JVM 再逐条解析——那正是这个接口要换掉的动作。
     * 这里只允许一条 {@code version()} 的 SELECT。
     */
    @Test
    void askingTheVersionReadsNothingButTheVersionRow() throws Exception {
        mvc.perform(get("/admin/api/content/version")
                        .header("Authorization", "Bearer " + asAdmin(2, "viewer")))
                .andExpect(status().isOk());

        verify(content, times(1)).version();
        verify(content, never()).allEnabled();
        verify(config, never()).allRaw();
    }

    /**
     * 这个数就是 {@code bundle()} 里带的那个 {@code version}：同一个来源、同一份读数。
     *
     * <p>两处各问一次库的话，面板的体检卡片会显示一个数而客户端拿到另一个数，
     * 于是"内容更没更"这件事在两边各说各话。
     */
    @Test
    void itReportsTheSameNumberTheBundleCarries() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/admin/api/content/version")
                            .header("Authorization", "Bearer " + asAdmin(2, "editor")))
                    .andExpect(jsonPath("$.data.contentVersion").value(27));
        }

        Map<String, Object> bundle = contentService.bundle();
        org.junit.jupiter.api.Assertions.assertEquals(27L, ((Number) bundle.get("version")).longValue(),
                "面板看到的数和客户端那包里标的那个必须是同一个来源");
        // 三次 HTTP 加一次整包组装，最多问两条 SELECT：版本号那份 1 秒缓存兜着，
        // 面板连点几下不该变成库上的一串读数
        verify(content, atMost(2)).version();
    }

    /* ---------------- 权限：与本控制器其余读接口同一条 ---------------- */

    /**
     * 只读角色读得动：这是"读不挡 viewer"那条老规矩，写侧的 {@code requireWriter} 不该蔓延到只读端点。
     * 三种角色同一条答复，顺带钉住"没有额外一档权限"——否则面板的 viewer 视图会缺一格数字。
     */
    @Test
    void everyAdminRoleReadsItIncludingTheReadOnlyOne() throws Exception {
        for (String role : new String[]{"viewer", "editor", "operator", "super"}) {
            mvc.perform(get("/admin/api/content/version")
                            .header("Authorization", "Bearer " + asAdmin(4, role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contentVersion").value(27));
        }
    }

    /** 没令牌 401，而且一次库都不问。 */
    @Test
    void anonymousIsRejectedBeforeTheVersionIsRead() throws Exception {
        mvc.perform(get("/admin/api/content/version")).andExpect(status().isUnauthorized());
        verify(content, never()).version();
    }

    /** 令牌没被撤销但账号已停用：403，同本控制器其余端点。 */
    @Test
    void aDisabledAdminCannotEvenReadTheVersion() throws Exception {
        mvc.perform(get("/admin/api/content/version")
                        .header("Authorization", "Bearer " + tokenFor(row(9L, "editor", 1, 0))))
                .andExpect(status().isForbidden());
    }

    /** 还没改初始口令的账号只放行自助改密，这一格数字也一样等一等。 */
    @Test
    void anAccountThatMustChangeItsPasswordIsHeldAtTheDoor() throws Exception {
        mvc.perform(get("/admin/api/content/version")
                        .header("Authorization", "Bearer " + tokenFor(row(8L, "editor", 0, 1))))
                .andExpect(status().isForbidden());
        verify(content, never()).version();
    }
}
