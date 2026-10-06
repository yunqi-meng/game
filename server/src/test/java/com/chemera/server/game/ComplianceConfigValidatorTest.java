package com.chemera.server.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合规配置（{@code curfew} / {@code app_version}）的写入守卫测试。
 *
 * <p>和 {@link AdConfigValidatorTest} 同样的立场：这两个键填坏了不会让服务器出错，只会静默改变
 * "哪些孩子能玩、哪些旧包进不来"。所以每一种静默失效都要在这里变成一条看得见的报错，
 * 并且拿 V8 种子的原文跑一遍正向用例——将来谁改了 {@code Content.Curfew} 的字段名或种子的一行，
 * 两边对不上会直接红。
 */
class ComplianceConfigValidatorTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 每份负例都带上合法 hint：闸门开着时缺 hint 本身就该报错，不带上它会污染"只应报一条"的断言。 */
    private static final String HINT = "\"hint\":\"周五六日 20-21 点可玩\"";

    private static List<String> bad(String key, String json) throws IOException {
        return ComplianceConfigValidator.problems(key, json == null ? null : OM.readTree(json));
    }

    private static List<String> curfew(String json) throws IOException {
        return bad("curfew", json);
    }

    /** 负例用：拼成一条完整对象，多余逗号由调用方控制，读起来还是一行一看就懂的配置。 */
    private static List<String> curfewBody(String... fields) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        for (String f : fields) sb.append(f).append(',');
        sb.append(HINT).append('}');
        return curfew(sb.toString());
    }

    /** 从 V8 迁移里把某个键的种子原文抠出来（'' 是 SQL 里的转义单引号）。 */
    private static String seeded(String key) throws IOException {
        String sql = new String(new ClassPathResource("db/migration/V8__android_compliance.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\('" + key + "',\\s*'(\\{.*?\\})',", Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "V8 里没解析出 " + key + " 种子行");
        return m.group(1).replace("''", "'");
    }

    /* ---------------- 正向：真正会写进库的两行必须原样通过 ---------------- */

    @Test
    void seededRowsPassTheGuard() throws IOException {
        List<String> c = curfew(seeded("curfew"));
        assertTrue(c.isEmpty(), "种子 curfew 应通过守卫，实际：" + c);
        List<String> v = bad("app_version", seeded("app_version"));
        assertTrue(v.isEmpty(), "种子 app_version 应通过守卫，实际：" + v);
    }

    @Test
    void seedStillRoundTripsIntoTheEngineModel() throws Exception {
        // 守卫认字段名，引擎也认字段名：两边各过一遍，防止"校验器跟着改、引擎没跟"
        Content.Curfew c = OM.readValue(seeded("curfew"), Content.Curfew.class);
        assertTrue(c.on());
        assertEquals("Asia/Shanghai", c.zoneOr().getId());
        assertEquals(List.of(5, 6, 7), c.daysOr());
        assertEquals("20:00", c.from());
        assertEquals("21:00", c.to());
        assertTrue(c.extraDatesOr().isEmpty());
        assertTrue(c.hintOr().contains("周五"));

        Content.AppVersion v = OM.readValue(seeded("app_version"), Content.AppVersion.class);
        assertEquals(1, v.minOr());
        assertEquals(1, v.latestOr());
    }

    @Test
    void nullAndEmptyAreLegalBecauseTheyMeanSomethingElse() throws IOException {
        assertTrue(curfew(null).isEmpty(), "删键=回退 Content.Curfew.DEFAULT，由 delete 分支处理");
        assertTrue(curfew("{}").isEmpty(), "只改分类/备注不动值，不该被拦");
        assertTrue(bad("app_version", "null").isEmpty());
        assertTrue(bad("quality", "{\"whatever\":1}").isEmpty(), "不是自己管的键就不该拦（分派按 cfg_key）");
    }

    @Test
    void crossMidnightWindowIsAccepted() throws IOException {
        assertTrue(curfewBody("\"days\":[5,6,7]","\"from\":\"22:00\"","\"to\":\"01:00\"").isEmpty(),
                "跨零点窗口是合法写法，闸门算得对，别把它当错误拦下");
    }

    /* ---------------- 负向：每一种"存得进去但把玩家关在门外"都要点名 ---------------- */

    @Test
    void misspelledFieldsAreRefusedBecauseJacksonWouldDropThem() throws IOException {
        List<String> out = curfew("{\"startTime\":\"20:00\",\"endTime\":\"21:00\",\"days\":[5,6,7]," + HINT + "}");
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("startTime") && out.get(0).contains("endTime"),
                "要点名拼错的字段：" + out);
    }

    @Test
    void unknownTimezoneIsRefused() throws IOException {
        List<String> out = curfewBody("\"zone\":\"Asia/Shangai\"", "\"days\":[5,6,7]",
                "\"from\":\"20:00\"", "\"to\":\"21:00\"");
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("Asia/Shangai") && out.get(0).contains("Asia/Shanghai"),
                "报错要同时给出坏值和正确写法：" + out);
    }

    @Test
    void malformedClockTimesAreRefused() throws IOException {
        assertTrue(curfewBody("\"from\":\"20点\"", "\"to\":\"21:00\"").stream()
                .anyMatch(s -> s.contains("20点")), "中文时刻要拦住");
        List<String> over = curfewBody("\"from\":\"25:00\"", "\"to\":\"26:00\"");
        assertEquals(2, over.size(), "越界小时两个字段都该点名：" + over);
    }

    @Test
    void zeroLengthWindowIsRefused() throws IOException {
        List<String> out = curfewBody("\"days\":[5,6,7]", "\"from\":\"20:00\"", "\"to\":\"20:00\"");
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("窗口长度为零"), out.toString());
    }

    @Test
    void noPlayDayAtAllIsRefusedEvenWhileTheSwitchIsOff() throws IOException {
        List<String> out = curfewBody("\"days\":[]", "\"from\":\"20:00\"", "\"to\":\"21:00\"");
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("永久无法游玩"), out.toString());
        List<String> off = curfew("{\"enabled\":false,\"days\":[]}");
        assertEquals(1, off.size(), off.toString());
        assertTrue(off.get(0).contains("此刻不影响结算"), "关掉时话要说清，但雷照样不许埋：" + off);
    }

    @Test
    void weekdayOutOfRangeIsRefused() throws IOException {
        List<String> out = curfewBody("\"days\":[0,8]");
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.stream().allMatch(s -> s.contains("1~7")), out.toString());
        assertTrue(curfew("{\"days\":\"5,6,7\"," + HINT + "}").stream()
                .anyMatch(s -> s.contains("必须是周几数组")));
    }

    @Test
    void holidayDatesMustBeIso() throws IOException {
        List<String> out = curfewBody("\"extraDates\":[\"2026-10-1\",\"10月1日\"]");
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(0).contains("yyyy-MM-dd"), out.toString());
        assertTrue(curfewBody("\"extraDates\":[\"2026-10-01\"]").isEmpty(), "补节假日是正常写法");
    }

    @Test
    void blankHintFallsBackButBadShapeAndLengthAreRefused() throws IOException {
        // 留空不拦是有意的：hintOr() 会回落到内置那句完整文案，玩家看到的不是空白
        assertTrue(curfew("{\"days\":[5],\"hint\":\"\"}").isEmpty(), "空文案有兜底，不该拦下整次保存");
        assertTrue(curfew("{\"days\":[5]}").isEmpty(), "缺字段同理");
        assertTrue(curfew("{\"days\":[5],\"hint\":\"" + "未成年".repeat(50) + "\"}")
                .stream().anyMatch(s -> s.contains("超过")), "超长文案要提醒会折屏");
        assertTrue(curfew("{\"days\":[5],\"hint\":123}").stream().anyMatch(s -> s.contains("必须是文本")));
    }

    @Test
    void wrongTopLevelTypesAreRefused() throws IOException {
        assertTrue(curfew("[1,2]").stream().anyMatch(s -> s.contains("必须是一个对象")));
        assertTrue(curfew("{\"enabled\":\"yes\"," + HINT + "}").stream().anyMatch(s -> s.contains("true/false")));
        assertTrue(curfew("{\"from\":20," + HINT + "}").stream().anyMatch(s -> s.contains("HH:mm")));
    }

    /* ---------------- 版本门 ---------------- */

    @Test
    void buildCeilingBelowFloorIsRefused() throws IOException {
        List<String> out = bad("app_version", "{\"minBuild\":9,\"latestBuild\":3}");
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("latestBuild") && out.get(0).contains("minBuild"), out.toString());
    }

    @Test
    void nonNumericOrNegativeBuildIsRefused() throws IOException {
        assertTrue(bad("app_version", "{\"minBuild\":\"1\"}").stream().anyMatch(s -> s.contains("minBuild")));
        assertTrue(bad("app_version", "{\"minBuild\":-1}").stream().anyMatch(s -> s.contains("minBuild")));
        assertTrue(bad("app_version", "{\"minBuild\":1.5}").stream().anyMatch(s -> s.contains("整数")));
        assertTrue(bad("app_version", "{\"minBuild\":5,\"latestBuild\":7}").isEmpty(), "正常写法不该拦");
    }

    @Test
    void urlMustLookLikeAUrl() throws IOException {
        assertTrue(bad("app_version", "{\"url\":\"ftp://a/b\"}").stream().anyMatch(s -> s.contains("http")));
        assertTrue(bad("app_version", "{\"url\":\"\"}").isEmpty(), "留空=不引导下载，合法");
        assertTrue(bad("app_version", "{\"url\":\"https://www.taptap.cn/x\"}").isEmpty());
    }

    @Test
    void schemaHandsThePanelTheSameListsTheValidatorUses() {
        Map<String, Object> s = ComplianceConfigValidator.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) s.get("curfew");
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) c.get("fields");
        assertEquals(new java.util.TreeSet<>(ComplianceConfigValidator.CURFEW_FIELDS), new java.util.TreeSet<>(fields),
                "面板拿到的字段清单必须就是校验器认的那一份，否则又会各写一套");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) c.get("defaults");
        assertEquals(List.of(5, 6, 7), d.get("days"), "缺省窗口由后端出，前端不抄第二份");
        assertEquals("20:00", d.get("from"));
        assertEquals("21:00", d.get("to"));
        assertEquals("Asia/Shanghai", d.get("zone"));
    }

    /* ---------------- 埋点采集开关（标量键） ---------------- */

    @Test
    void analyticsSwitchOnlyAcceptsBoolean() throws IOException {
        assertTrue(bad("analytics_enabled", "true").isEmpty(), "开是现行行为");
        assertTrue(bad("analytics_enabled", "false").isEmpty(), "显式关停是有意的合规决定");
        assertTrue(bad("analytics_enabled", "null").isEmpty(), "删键=回默认（开）");
        List<String> str = bad("analytics_enabled", "\"false\"");
        assertEquals(1, str.size(), str.toString());
        assertTrue(str.get(0).contains("true 或 false"), "字符串要拦住并给出正确写法：" + str);
        assertTrue(bad("analytics_enabled", "0").stream().anyMatch(s -> s.contains("静默")),
                "填 0 会被 coerce 成关停，而且关停不会有任何报错，必须点名：" + bad("analytics_enabled", "0"));
    }

    @Test
    void analyticsEngineReadIsExactlyWhatTheGuardAllows() throws Exception {
        // 校验器只放布尔进来，而引擎的读法必须是"只有显式 false 才关"：
        // 两边一旦错位（比如哪天改成 Boolean.TRUE.equals），就会出现"运营拨了关停、数据照收"的合规事故。
        assertTrue(config(Map.of()).analyticsOn(), "缺省按开：不能因为种子里没这行就停止采集");
        assertTrue(config(Map.of("analytics_enabled", true)).analyticsOn());
        assertFalse(config(Map.of("analytics_enabled", false)).analyticsOn(), "显式 false 必须真的关掉");
    }

    /** 走引擎真实的装配路径（Jackson convertValue → Content.Config），而不是手工 new 一个 record。 */
    private static Content.Config config(Map<String, Object> cfg) {
        return OM.convertValue(cfg, Content.Config.class);
    }

    @Test
    void schemaPublishesTheAnalyticsDefault() {
        Map<String, Object> s = ComplianceConfigValidator.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) s.get("analytics_enabled");
        assertNotNull(a, "面板要能问到这个键的默认值，否则开关显示成空");
        assertEquals("boolean", a.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) a.get("defaults");
        assertEquals(Boolean.TRUE, d.get("analytics_enabled"));
    }
}
