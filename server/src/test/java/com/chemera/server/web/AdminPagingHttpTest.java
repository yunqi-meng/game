package com.chemera.server.web;

import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.common.Page;
import com.chemera.server.controller.admin.AdminConfigController;
import com.chemera.server.controller.admin.AdminContentController;
import com.chemera.server.controller.admin.AdminModerationController;
import com.chemera.server.controller.admin.AdminSupport;
import com.chemera.server.controller.admin.AdminUserController;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.entity.AppConfigRow;
import com.chemera.server.game.ContentRegistry;
import com.chemera.server.game.CurfewGuard;
import com.chemera.server.game.SensitiveFilter;
import com.chemera.server.mapper.AdminMapper;
import com.chemera.server.mapper.AuditMapper;
import com.chemera.server.mapper.ConfigMapper;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.ContentRevisionMapper;
import com.chemera.server.mapper.ModerationMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.security.AdminInterceptor;
import com.chemera.server.security.JwtService;
import com.chemera.server.service.AccountPurge;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.AuthService;
import com.chemera.server.service.ContentHealthService;
import com.chemera.server.service.ContentRevisionService;
import com.chemera.server.service.ContentService;
import com.chemera.server.service.PlayerAssetService;
import com.chemera.server.service.SaveService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 后台分页列表在 HTTP 层的样子（H6-3）。
 *
 * <p>这一层要钉的只有一句话：<strong>总数是库回答的，不是本页行数算出来的</strong>。
 * 【审核】那两个列表以前回裸 {@code List}，面板只好拿本页长度编一个总数
 * （满页时说"还有下一页"、最后一页多报一条）。现在六个列表端点共用 {@link Page}，
 * 桩故意把 {@code page(...)} 摆 2 行、{@code count(...)} 报几百行——只要实现回的是"本页长度"，
 * 断言里的数字当场就对不上。
 *
 * <p>用真控制器 + 假 mapper：这里的裁判对象是<b>出参形状与参数归一</b>（键名就叫 {@code rows}/{@code total}，
 * 是面板读的那两个词），不是 SQL 结果——后者归 e2e（真 MySQL 逐页走一遍）与
 * {@code MapperQueryHygieneTest}（count 与 page 不许各抄一份 WHERE）。
 */
class AdminPagingHttpTest {

    private ModerationMapper mod;
    private AuditMapper auditMapper;
    private ConfigMapper config;
    private ContentMapper content;
    private ContentHealthService health;
    private UserMapper users;
    private JwtService jwt;
    private AdminMapper admins;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mod = mock(ModerationMapper.class);
        auditMapper = mock(AuditMapper.class);
        config = mock(ConfigMapper.class);
        content = mock(ContentMapper.class);
        health = mock(ContentHealthService.class);
        users = mock(UserMapper.class);
        ContentRevisionMapper revs = mock(ContentRevisionMapper.class);
        SensitiveFilter words = mock(SensitiveFilter.class);
        ContentRegistry registry = mock(ContentRegistry.class);
        ContentService contentService = mock(ContentService.class);
        ObjectMapper om = new ObjectMapper();
        AuditService audit = new AuditService(auditMapper, om);
        AdminSupport support = new AdminSupport(content, contentService);
        jwt = new JwtService("paging-http-layer-test-secret!!32bytes", 60);
        admins = mock(AdminMapper.class);

        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminModerationController(mod, support, audit, words),
                        new AdminConfigController(config, support, audit, om),
                        new AdminContentController(content, support, audit, om, registry, contentService,
                                new ContentRevisionService(revs, content), health),
                        new AdminUserController(users, mock(SaveService.class), mock(PlayerAssetService.class),
                                mock(SessionMapper.class), mock(AuthService.class), mock(AccountPurge.class),
                                support, audit, mock(CurfewGuard.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(), wireConverter())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();

        // 每页 2 行，库里各有几百行：这两个数字必须分开回，混成一个就正是 H6-3 要治的病。
        when(mod.pageReports(anyString(), anyInt(), anyInt())).thenReturn(List.of(Map.of("id", 11), Map.of("id", 12)));
        when(mod.countReports(anyString())).thenReturn(137L);
        when(auditMapper.page(anyInt(), anyInt())).thenReturn(List.of(Map.of("id", 90), Map.of("id", 89)));
        when(auditMapper.count()).thenReturn(412L);
        when(mod.pageWords(anyInt(), anyInt())).thenReturn(List.of(Map.of("id", 7), Map.of("id", 6)));
        when(mod.countWords()).thenReturn(9L);
        when(config.page(anyString(), anyInt(), anyInt())).thenReturn(List.of(cfgRow("ad"), cfgRow("level_exp")));
        when(config.count(anyString())).thenReturn(22L);
        when(content.page(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(Map.of("itemId", "H"), Map.of("itemId", "O")));
        when(content.count(anyString(), anyString())).thenReturn(458L);
        when(users.page(anyString(), anyInt(), anyInt())).thenReturn(List.of(Map.of("id", 3), Map.of("id", 4)));
        when(users.count(anyString())).thenReturn(1000L);
    }

    /** 出参形状跟线上一致（non_null + ISO 日期），见 {@code AdminOptimisticLockHttpTest#wireConverter} 的理由。 */
    private static MappingJackson2HttpMessageConverter wireConverter() {
        ObjectMapper wire = Jackson2ObjectMapperBuilder.json()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return new MappingJackson2HttpMessageConverter(wire);
    }

    private static AppConfigRow cfgRow(String key) {
        AppConfigRow r = new AppConfigRow();
        r.setCfgKey(key); r.setCfgValue("0"); r.setCategory("general");
        r.setUpdatedAt(LocalDateTime.of(2026, 10, 6, 5, 20, 11, 123_000_000));
        r.setUpdatedBy("ops");
        return r;
    }

    private String asAdmin() {
        AdminUser a = new AdminUser();
        a.setId(2L); a.setUsername("ops"); a.setRole("super"); a.setStatus(0);
        a.setMustChangePassword(0); a.setPassHash("h");
        when(admins.findById(2L)).thenReturn(a);
        return jwt.issue(2, "admin", "super", "ops");
    }

    /* ---------------- 六个列表共用同一个形状 ---------------- */

    /**
     * 六个能分页的列表端点都回 {@code {rows,total}}，且那个 {@code total} 是 {@code COUNT(*)} 给的数，
     * 不是本页行数（桩里本页 2 行）。一条一条点名，是为了让"漏掉第七个"在红字里直接看得见名字。
     */
    @Test
    void everyPagedListCarriesRowsAndARealTotal() throws Exception {
        String[][] endpoints = {
                {"/admin/api/moderation/reports", "137"},
                {"/admin/api/moderation/audit", "412"},
                {"/admin/api/moderation/words", "9"},
                {"/admin/api/config", "22"},
                {"/admin/api/content/items?type=element", "458"},
                {"/admin/api/users", "1000"},
        };
        for (String[] e : endpoints) {
            mvc.perform(get(e[0]).header("Authorization", "Bearer " + asAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.rows").isArray())
                    .andExpect(jsonPath("$.data.rows.length()").value(2))
                    // 键名就是面板读的那两个词；总数与本页长度这里是两个数
                    .andExpect(jsonPath("$.data.total").value(Integer.parseInt(e[1])));
        }
    }

    /** 筛选条件必须同时作用在列表和总数上：只筛列表不筛总数，页数会按全表算，搜完还是"共 137 条"。 */
    @Test
    void theFilterReachesTheCountToo() throws Exception {
        when(mod.countReports("open")).thenReturn(3L);
        mvc.perform(get("/admin/api/moderation/reports").param("status", "open")
                        .header("Authorization", "Bearer " + asAdmin()))
                .andExpect(jsonPath("$.data.total").value(3));
        verify(mod).pageReports("open", 50, 0);
        verify(mod).countReports("open");

        when(config.count("ad")).thenReturn(1L);
        mvc.perform(get("/admin/api/config").param("q", "ad")
                        .header("Authorization", "Bearer " + asAdmin()))
                .andExpect(jsonPath("$.data.total").value(1));
        verify(config).page("ad", 20, 0);
        verify(config).count("ad");
    }

    /* ---------------- 参数归一：页长有上限，偏移不为负 ---------------- */

    /**
     * 页长一律收到 {@link Page#MAX_SIZE}，偏移负数当 0。
     *
     * <p>以前是每个控制器各写一遍 {@code Math.min(size, 200)}，而【内容】那处写的是 500，
     * 【审核】和【配置】压根没写；{@code off=-1} 会一路走到 MySQL 报语法错，运营看到的是一句 500。
     */
    @Test
    void pageSizeAndOffsetAreNormalisedBeforeTheSql() throws Exception {
        mvc.perform(get("/admin/api/moderation/audit").param("size", "99999").param("off", "-7")
                        .header("Authorization", "Bearer " + asAdmin()))
                .andExpect(status().isOk());
        verify(auditMapper).page(Page.MAX_SIZE, 0);

        mvc.perform(get("/admin/api/content/items").param("type", "element")
                        .param("size", "99999").param("off", "-5")
                        .header("Authorization", "Bearer " + asAdmin()))
                .andExpect(status().isOk());
        verify(content).page(eq("element"), anyString(), eq(Page.MAX_SIZE), eq(0));

        mvc.perform(get("/admin/api/config").param("size", "0")
                        .header("Authorization", "Bearer " + asAdmin()))
                .andExpect(status().isOk());
        verify(config).page(anyString(), eq(20), eq(0));   // size=0 不是"给我 0 行"，是"没说页数"
    }

    /** 词表分页排在第一页的是最新那条（面板加完词要在第一页看见自己刚加的那行）。 */
    @Test
    void wordsPageIsNewestFirst() throws Exception {
        when(mod.pageWords(50, 0)).thenReturn(List.of(Map.of("id", 12, "word", "zui")));
        when(mod.countWords()).thenReturn(1L);
        mvc.perform(get("/admin/api/moderation/words").header("Authorization", "Bearer " + asAdmin()))
                .andExpect(jsonPath("$.data.rows[0].word").value("zui"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    /* ---------------- 体检缓存的接线（判定本身见 ContentHealthServiceTest） ---------------- */

    /**
     * 面板那个【重新体检】按钮要真的能把缓存绕过去：不带参数时允许吃缓存（那正是省下来的整表扫描），
     * {@code fresh=true} 必须传到服务层。这条断言守的是接线——服务再正确，
     * 前端点按钮却打到吃缓存的那条路，用户看到的仍然是旧结果。
     */
    @Test
    void healthServesCachedByDefaultAndRescansOnDemand() throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("checked", 458);
        body.put("ok", true);
        body.put("fromCache", true);
        when(health.report(false)).thenReturn(body);
        when(health.report(true)).thenReturn(new java.util.LinkedHashMap<>(Map.of("checked", 459, "ok", true)));

        mvc.perform(get("/admin/api/content/health").header("Authorization", "Bearer " + asAdmin()))
                .andExpect(jsonPath("$.data.fromCache").value(true))
                .andExpect(jsonPath("$.data.checked").value(458));
        mvc.perform(get("/admin/api/content/health").param("fresh", "true")
                        .header("Authorization", "Bearer " + asAdmin()))
                .andExpect(jsonPath("$.data.fromCache").doesNotExist())   // 重扫那次不带这个字段
                .andExpect(jsonPath("$.data.checked").value(459));
        verify(health).report(false);
        verify(health).report(true);
    }

    /**
     * 反证（这一层自己的哨兵）：把 {@code Page} 喂成"总数=本页长度"那种旧形状，判据必须抓得住。
     *
     * <p>{@link Page} 的构造点就是 {@code everyPagedListCarriesRowsAndARealTotal} 读的那个形状，
     * 一旦有人把它退回裸 List（面板那头就只剩本页长度可用），红的是这里。
     */
    @Test
    void pageCannotSilentlyDegradeToPageLength() {
        Page<Map<String, Object>> p = new Page<>(List.of(Map.of("id", 1), Map.of("id", 2)), 137L);
        org.junit.jupiter.api.Assertions.assertNotEquals(p.rows().size(), (int) p.total(),
                "本页行数与总数这里是两个来源；把它写成同一个数，就等于把 H6-3 删掉了");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Page<Map<String, Object>>(null, 0L),
                "rows 为 null 会被 non_null 抹掉整个字段：面板读到 undefined 才知道坏了，得在构造点就拦下");
    }
}
