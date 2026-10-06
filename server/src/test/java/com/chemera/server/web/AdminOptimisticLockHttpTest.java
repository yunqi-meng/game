package com.chemera.server.web;

import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.admin.AdminConfigController;
import com.chemera.server.controller.admin.AdminContentController;
import com.chemera.server.controller.admin.AdminSupport;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.entity.AppConfigRow;
import com.chemera.server.entity.ContentItem;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 乐观锁在 HTTP 层的样子（H6-2）。
 *
 * <p>{@code ContentRevisionServiceTest} 判的是"条件不成立时一行都不写、一条历史都不留"，
 * 这一层判的是<strong>它对外长什么样</strong>：面板要按状态码分流到「载入最新」那个弹窗，
 * 所以冲突必须是真 409。这仓库里业务错误一贯是 200 + {@code ok:false}（便于客户端统一处理），
 * 一旦有人"顺手统一一下"把 409 收回去，服务端的条件写照样严丝合缝，界面却会退化成
 * "一行飘过去的红字"——两个运营接着互相覆盖，而且没人觉得自己错了。
 *
 * <p>这里用真 {@code ContentRevisionService} + 假 mapper：判断的顺序（条件先于快照）
 * 是被这一趟走出来的，不是被断言复述的。
 */
class AdminOptimisticLockHttpTest {

    private static final String ROW = "element:H";
    private static final LocalDateTime LIVE = LocalDateTime.of(2026, 10, 6, 5, 20, 11, 123_000_000);

    private ContentMapper content;
    private ContentRevisionMapper revs;
    private ConfigMapper config;
    private AuditService audit;
    private JwtService jwt;
    private AdminMapper admins;
    private MockMvc contentMvc;
    private MockMvc configMvc;

    @BeforeEach
    void setUp() {
        content = mock(ContentMapper.class);
        revs = mock(ContentRevisionMapper.class);
        config = mock(ConfigMapper.class);
        audit = mock(AuditService.class);
        admins = mock(AdminMapper.class);
        jwt = new JwtService("optimistic-lock-http-layer-secret!!", 60);

        ContentService contentService = mock(ContentService.class);
        AdminSupport support = new AdminSupport(content, contentService);
        ContentRevisionService revisions = new ContentRevisionService(revs, content);
        ContentRegistry registry = mock(ContentRegistry.class);
        ObjectMapper om = new ObjectMapper();
        // 体检从控制器里搬进了 ContentHealthService（H6-3），构造点跟着多一个参数；
        // 这一组测试打的是 /item 与 /config，不碰 /health，所以给它真的服务而不是又一层桩。
        ContentHealthService health = new ContentHealthService(content, registry, om);

        contentMvc = MockMvcBuilders.standaloneSetup(
                        new AdminContentController(content, support, audit, om, registry, contentService,
                                revisions, health))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(), wireConverter())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();
        configMvc = MockMvcBuilders.standaloneSetup(
                        new AdminConfigController(config, support, audit, om))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(), wireConverter())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();

        // 库里躺着的那一行：同事在我们打开对话框之后刚改过它
        when(content.get("element", "H")).thenReturn(liveRow());
    }

    /**
     * 出参形状跟线上一致（{@code application.yml:13} 的 non_null + Boot 默认的 ISO 日期）。
     *
     * <p>MockMvc 的默认转换器用裸 {@code ObjectMapper}，会把 {@code LocalDateTime} 序列化成
     * {@code [2026,10,6,5,23,11,123000000]} 这种数组；面板拿到那个是没法当版本号回传的。
     * 这一层要断言的就是"浏览器实际读到的那一串"，所以先把线上形状装上去，再谈断言。
     */
    private static MappingJackson2HttpMessageConverter wireConverter() {
        ObjectMapper wire = Jackson2ObjectMapperBuilder.json()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return new MappingJackson2HttpMessageConverter(wire);
    }

    private static ContentItem liveRow() {        ContentItem it = new ContentItem();
        it.setContentType("element"); it.setItemId("H");
        it.setName("氢"); it.setSort(1); it.setEnabled(1);
        it.setData("{\"id\":\"H\",\"zh\":\"氢\"}");
        it.setUpdatedAt(LIVE.plusMinutes(3));
        it.setUpdatedBy("同事");
        return it;
    }

    private String asAdmin(long id, String roleInDb) {
        AdminUser a = new AdminUser();
        a.setId(id); a.setUsername("ops"); a.setRole(roleInDb); a.setStatus(0);
        a.setMustChangePassword(0); a.setPassHash("h");
        when(admins.findById(id)).thenReturn(a);
        // 令牌里声明 super：验证裁决看的是库里的角色，不是令牌里的
        return jwt.issue(id, "admin", "super", "ops");
    }

    private org.springframework.test.web.servlet.ResultActions putItem(String token, String expect) throws Exception {
        String body = "{\"type\":\"element\",\"data\":{\"id\":\"H\",\"zh\":\"氢-我的改动\"},\"sort\":1,\"enabled\":1}";
        var req = put("/admin/api/content/item").header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
        if (expect != null) req.param("expect", expect);
        return contentMvc.perform(req);
    }

    /* ---------------- 内容 ---------------- */

    /** 状态码本身就是这条防线的接口契约：文案会改字，409 不会。 */
    @Test
    void staleExpectIsAReal409WithAGuidanceMessage() throws Exception {
        when(content.updateIfUnchanged(any(), any())).thenReturn(0);
        putItem(asAdmin(2, "editor"), "2026-01-01T00:00:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.tag").value("CONFLICT"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("在你编辑期间已被")));
        verify(content, never()).upsert(any());
        verify(revs, never()).insert(any());
    }

    /** 面板手上的版本号是列表给的，所以列表必须真的带着它——否则整个前提塌了。 */
    @Test
    void listCarriesTheVersionTokenThePanelWillSendBack() throws Exception {
        when(content.page(eq("element"), anyString(), anyInt(), anyInt())).thenReturn(java.util.List.of(
                new java.util.LinkedHashMap<String, Object>(java.util.Map.of(
                        "contentType", "element", "itemId", "H", "name", "氢",
                        "sort", 1, "enabled", 1, "updatedAt", LIVE.toString(), "updatedBy", "同事"))));
        contentMvc.perform(get("/admin/api/content/items?type=element&q=&size=50&off=0")
                        .header("Authorization", "Bearer " + asAdmin(2, "editor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].updatedAt").value(LIVE.toString()));
    }

    /** 版本号对上才写，并且回体必须给出写完那一行的新号：否则同一个对话框里第二次保存会撞上自己。 */
    @Test
    void matchingExpectWritesAndReturnsTheNewStamp() throws Exception {
        when(content.updateIfUnchanged(any(), any())).thenReturn(1);
        putItem(asAdmin(2, "editor"), LIVE.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedAt").value(liveRow().getUpdatedAt().toString()));
        verify(content).updateIfUnchanged(any(), eq(LIVE));
        verify(revs).insert(any());   // G3 那条纪律没被乐观锁带跑：成功的那次照样留档
    }

    /**
     * 版本号读不懂：这是面板自己送错了东西（它该送列表给的那个号），不是两个人撞车。
     *
     * <p>所以它<strong>不能</strong>是 409。409 在面板那边接的是「载入最新」——照那条路走，
     * 一次手滑就把运营没保存的草稿换成了别人那一版，而他以为只是"保存失败了"。
     * 这一仓库只有冲突值得用真状态码告诉客户端"要分流"，其余业务错误仍是 200 + {@code ok:false}。
     */
    @Test
    void unparsableExpectIsCode400NotAConflict() throws Exception {
        putItem(asAdmin(2, "editor"), "昨天下午")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.tag").doesNotExist());
        verify(content, never()).updateIfUnchanged(any(), any());
        verify(content, never()).upsert(any());
    }

    /** 新增态：面板说"这行还不该存在"，撞上已有键就绝不能退化成覆盖写。 */
    @Test
    void newExpectNeverSilentlyOverwritesACreatedRow() throws Exception {
        when(content.insertOnly(any())).thenThrow(new DuplicateKeyException("dup"));
        putItem(asAdmin(2, "editor"), "none")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("建出来")));
        verify(content, never()).upsert(any());
    }

    /** 不带 expect 保持覆盖写：没有逐行版本号的批量写入（回填脚本、回归里的 hput）走的就是这条路。 */
    @Test
    void absentExpectStillOverwrites() throws Exception {
        putItem(asAdmin(2, "editor"), null).andExpect(status().isOk());
        verify(content).upsert(any());
    }

    @Test
    void toggleAndDeleteAlsoHonourTheCondition() throws Exception {
        when(content.setEnabledIfUnchanged(anyString(), anyString(), anyInt(), anyString(), any())).thenReturn(0);
        contentMvc.perform(post("/admin/api/content/toggle?type=element&id=H&enabled=0&expect=2026-01-01T00:00:00")
                        .header("Authorization", "Bearer " + asAdmin(2, "editor")))
                .andExpect(status().isConflict());
        verify(content, never()).setEnabled(anyString(), anyString(), anyInt(), anyString());

        when(content.deleteIfUnchanged(anyString(), anyString(), any())).thenReturn(0);
        contentMvc.perform(delete("/admin/api/content/item?type=element&id=H&expect=2026-01-01T00:00:00")
                        .header("Authorization", "Bearer " + asAdmin(2, "editor")))
                .andExpect(status().isConflict());
        verify(content, never()).delete(anyString(), anyString());
    }

    /**
     * 回滚也收版本号：它写的同样是 live 行。不守这条的话，运营看见保存报 409 之后的第一个动作
     * 就是"那就回滚一版试试"，那条防线等于留了一扇后门。
     *
     * <p>选的是 {@code enabled=0} 那一版：G3 给它开的是"校验一个玩家看不见的行没有意义"这个口子，
     * 于是这一趟只走乐观锁这一条判断，不会被内容校验先拦掉。
     */
    @Test
    void rollbackIsTheSameDoorAndTakesTheSameKey() throws Exception {
        com.chemera.server.entity.ContentRevision off = new com.chemera.server.entity.ContentRevision();
        off.setId(5L); off.setContentType("element"); off.setItemId("H");
        off.setName("氢"); off.setSort(1); off.setEnabled(0);
        off.setDataJson("{\"id\":\"H\",\"zh\":\"氢-旧版\"}"); off.setVersion(21L);
        when(revs.get(5L)).thenReturn(off);
        when(content.updateIfUnchanged(any(), any())).thenReturn(0);

        contentMvc.perform(post("/admin/api/content/rollback?rev=5&expect=2026-01-01T00:00:00")
                        .header("Authorization", "Bearer " + asAdmin(2, "editor")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.tag").value("CONFLICT"));
        verify(content, never()).upsert(any());
        verify(revs, never()).insert(any());
    }

    /** 只读角色连门都进不去，谈不上冲突（403 而不是 409：他那份改动本来就无权提交）。 */    @Test
    void viewerIsRejectedBeforeAnyConditionIsChecked() throws Exception {
        putItem(asAdmin(3, "viewer"), "2026-01-01T00:00:00").andExpect(status().isForbidden());
        verify(content, never()).updateIfUnchanged(any(), any());
    }

    /* ---------------- 配置 ---------------- */

    private org.springframework.test.web.servlet.ResultActions putConfig(String token, String expect) throws Exception {
        var req = put("/admin/api/config").header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"key\":\"daily_gift_coins\",\"value\":88,\"category\":\"economy\",\"remark\":\"e2e\"}");
        if (expect != null) req.param("expect", expect);
        return configMvc.perform(req);
    }

    private static AppConfigRow configRow() {
        AppConfigRow r = new AppConfigRow();
        r.setCfgKey("daily_gift_coins"); r.setCfgValue("77");
        r.setCategory("economy"); r.setRemark("别人的那份");
        r.setUpdatedAt(LIVE.plusMinutes(5)); r.setUpdatedBy("同事");
        return r;
    }

    @Test
    void staleConfigExpectIsA409AndWritesNothing() throws Exception {
        when(config.updateIfUnchanged(any(), any())).thenReturn(0);
        when(config.get("daily_gift_coins")).thenReturn(configRow());
        putConfig(asAdmin(2, "editor"), LIVE.toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.tag").value("CONFLICT"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("daily_gift_coins")));
        verify(config, never()).upsert(any());
        // 冲突的那一次连"发布给客户端"都不该发生：缓存里还是同事那份，玩家看到的和库里一致
        verify(content, never()).bumpVersion();
    }

    @Test
    void matchingConfigExpectWritesAndReturnsTheNewStamp() throws Exception {
        when(config.updateIfUnchanged(any(), any())).thenReturn(1);
        when(config.get("daily_gift_coins")).thenReturn(configRow());
        putConfig(asAdmin(2, "editor"), LIVE.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedAt").value(configRow().getUpdatedAt().toString()));
        verify(content).bumpVersion();
    }

    @Test
    void newConfigKeyNeverOverwritesWhatSomeoneJustCreated() throws Exception {
        when(config.insertOnly(any())).thenThrow(new DuplicateKeyException("dup"));
        when(config.get("daily_gift_coins")).thenReturn(configRow());
        putConfig(asAdmin(2, "editor"), "none")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("建出来")));
        verify(config, never()).upsert(any());
    }

    /** 校验排在条件写之前：一个本来就该被拒的值，没必要再告诉运营"版本冲突"。 */
    @Test
    void invalidValueIsRejectedAsABusinessErrorEvenWithAFreshStamp() throws Exception {
        configMvc.perform(put("/admin/api/config?expect=" + LIVE)
                        .header("Authorization", "Bearer " + asAdmin(2, "editor"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"curfew\",\"value\":{\"enabled\":true,\"days\":[]},\"category\":\"guard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value(400));
        verify(config, never()).updateIfUnchanged(any(), any());
    }

    @Test
    void staleConfigDeleteIsA409AndKeepsTheRow() throws Exception {
        when(config.deleteIfUnchanged(eq("daily_gift_coins"), any())).thenReturn(0);
        when(config.get("daily_gift_coins")).thenReturn(configRow());
        configMvc.perform(delete("/admin/api/config?key=daily_gift_coins&expect=2026-01-01T00:00:00")
                        .header("Authorization", "Bearer " + asAdmin(2, "editor")))
                .andExpect(status().isConflict());
        verify(config, never()).delete(anyString());
    }
}
