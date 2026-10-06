package com.chemera.server.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 广告目录写入守卫的测试。
 *
 * <p>它守的不是"代码会不会崩"，而是"运营改完广告位，玩家看完广告到底发不发得出东西"：
 * 引擎对坏行是静默跳过，所以这里必须把每一种静默失效都写成一条能看见的报错。
 * 另外两条正向用例（内置默认目录、V7 种子原文）是防漂移的根——
 * 将来谁改了 {@code Content.AdConfig} 的字段名或种子里的一行，两边对不上会直接红。
 */
class AdConfigValidatorTest {

    private static final ObjectMapper om = new ObjectMapper();

    private static List<String> problems(String json) throws IOException {
        return AdConfigValidator.problems(om.readTree(json));
    }

    /* ---------- 正向：真实发出去的两份目录必须原样通过 ---------- */

    @Test
    void seededCatalogPasses() throws IOException {
        String sql = new String(new ClassPathResource("db/migration/V7__ad_reward.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\('ad',\\s*'(\\{.*?\\})',", Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "V7 里没解析出 ad 种子行");
        List<String> bad = problems(m.group(1).replace("''", "'"));
        assertTrue(bad.isEmpty(), "种子目录应通过守卫，实际：" + bad);
    }

    @Test
    void emptyCatalogAndMissingValueAreLegal() throws IOException {
        assertTrue(problems("{\"slots\":[],\"unlocks\":[]}").isEmpty(), "清空目录是合法的下架动作");
        assertTrue(problems("null").isEmpty(), "整键为空=回退内置默认，由 delete 分支处理");
        assertTrue(problems("{}").isEmpty(), "只改分类/备注不动值，不该被拦");
    }

    /* ---------- 负向：每一种"存得进去但不发奖"都要点名 ---------- */

    @Test
    void unknownRewardTypeIsRefused() throws IOException {
        String json = "{\"slots\":[{\"kind\":\"boom\",\"zh\":\"慰问金\",\"reward\":\"coin\",\"amount\":500,\"daily\":2,\"cooldownSec\":0}]}";
        List<String> bad = problems(json);
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).contains("coin") && bad.get(0).contains("coins"),
                "报错要同时指出坏值和可用清单，运营才知道该填什么：" + bad);
    }

    @Test
    void missingRewardIsRefusedRatherThanSilentlySkipped() throws IOException {
        List<String> bad = problems("{\"slots\":[{\"kind\":\"boom\",\"zh\":\"慰问金\",\"amount\":500,\"daily\":2}]}");
        assertTrue(bad.stream().anyMatch(s -> s.contains("缺少 reward")), bad.toString());
    }

    @Test
    void typoInFieldNameIsRefusedBecauseJacksonWouldDropIt() throws IOException {
        List<String> bad = problems("{\"slots\":[{\"kind\":\"boom\",\"zh\":\"慰问金\",\"reward\":\"coins\","
                + "\"amount\":500,\"daily\":2,\"cooldown\":300}],\"unlock\":[]}");
        assertEquals(2, bad.size(), bad.toString());
        assertTrue(bad.stream().anyMatch(s -> s.contains("cooldown")), "拼错的槽位字段：" + bad);
        assertTrue(bad.stream().anyMatch(s -> s.contains("unlock") && s.contains("ad")), "拼错的顶层字段：" + bad);
    }

    @Test
    void duplicateKindOrIdIsRefused() throws IOException {
        List<String> dupKind = problems("{\"slots\":[{\"kind\":\"boom\",\"zh\":\"A\",\"reward\":\"coins\",\"amount\":1,\"daily\":1,\"cooldownSec\":0},"
                + "{\"kind\":\"boom\",\"zh\":\"B\",\"reward\":\"coins\",\"amount\":2,\"daily\":1,\"cooldownSec\":0}]}");
        assertTrue(dupKind.stream().anyMatch(s -> s.contains("重复")), dupKind.toString());
        List<String> dupId = problems("{\"unlocks\":[{\"id\":\"elpack\",\"zh\":\"A\",\"cost\":1,\"reward\":\"pack_el\"},"
                + "{\"id\":\"elpack\",\"zh\":\"B\",\"cost\":2,\"reward\":\"pack_el\"}]}");
        assertTrue(dupId.stream().anyMatch(s -> s.contains("重复")), dupId.toString());
    }

    @Test
    void zeroAmountOnlyBannedWhereTheNumberActuallySettles() throws IOException {
        List<String> coins = problems("{\"slots\":[{\"kind\":\"boom\",\"zh\":\"A\",\"reward\":\"coins\",\"amount\":0,\"daily\":1,\"cooldownSec\":0}]}");
        assertTrue(coins.stream().anyMatch(s -> s.contains("amount")), "金币填 0 等于播完不发：" + coins);
        assertTrue(problems("{\"slots\":[{\"kind\":\"dbl\",\"zh\":\"A\",\"reward\":\"coupon\",\"daily\":1,\"cooldownSec\":0}]}").isEmpty(),
                "双倍券是『有没有』型奖励，不要求填数量（内置种子就没填）");
    }

    @Test
    void outOfRangeGatesAreRefusedWithTheReason() throws IOException {
        List<String> ttl = problems("{\"ticketTtlSec\":5}");
        assertTrue(ttl.stream().anyMatch(s -> s.contains("ticketTtlSec") && s.contains("900")),
                "低于 30 秒会被引擎回落成 900，必须说清" + ttl);
        assertTrue(problems("{\"enabled\":false,\"ticketTtlSec\":5}").get(0).contains("总开关是关的"),
                "关着的时候也要拦，但话要说清此刻不影响结算");
        assertTrue(problems("{\"minLevel\":999}").stream().anyMatch(s -> s.contains("minLevel")));
        assertTrue(problems("{\"dailyTotal\":-1}").stream().anyMatch(s -> s.contains("dailyTotal")));
        assertTrue(problems("{\"viewPoints\":\"one\"}").stream().anyMatch(s -> s.contains("整数")));
    }

    @Test
    void skinUnlockNeedsATargetTheEngineKnows() throws IOException {
        assertTrue(problems("{\"unlocks\":[{\"id\":\"s1\",\"zh\":\"皮\",\"cost\":1,\"reward\":\"skin\",\"once\":true}]}")
                .stream().anyMatch(s -> s.contains("target")), "皮肤不给 target 发的是无名皮肤");
        assertTrue(problems("{\"unlocks\":[{\"id\":\"s1\",\"zh\":\"皮\",\"cost\":1,\"reward\":\"skin\",\"target\":\"golden\",\"once\":true}]}")
                .stream().anyMatch(s -> s.contains("golden")), "引擎里没有的皮肤要点名");
        assertTrue(problems("{\"unlocks\":[{\"id\":\"s1\",\"zh\":\"皮\",\"cost\":1,\"reward\":\"pack_el\",\"target\":\"cyber\",\"once\":true}]}")
                .stream().anyMatch(s -> s.contains("不会被读取")), "多余的 target 也要说，否则以为它有用");
    }

    @Test
    void kindAndIdMustBeUsableAsIdentifiers() throws IOException {
        assertTrue(problems("{\"slots\":[{\"kind\":\"看广告得金币\",\"zh\":\"A\",\"reward\":\"coins\",\"amount\":1,\"daily\":1,\"cooldownSec\":0}]}")
                .stream().anyMatch(s -> s.contains("字母")));
        assertTrue(problems("{\"unlocks\":[{\"id\":\"El-Pack\",\"zh\":\"A\",\"cost\":1,\"reward\":\"pack_el\"}]}")
                .stream().anyMatch(s -> s.contains("小写")));
    }

    @Test
    void nonObjectValueIsRefused() throws IOException {
        assertTrue(problems("[1,2]").stream().anyMatch(s -> s.contains("对象")));
        assertTrue(problems("123").stream().anyMatch(s -> s.contains("对象")));
        assertTrue(problems("{\"slots\":3}").stream().anyMatch(s -> s.contains("数组")));
    }

    /** 面板的下拉靠这份说明书生成：它必须和引擎认的集合一模一样，前端不再抄一份。 */
    @Test
    @SuppressWarnings("unchecked")
    void schemaMirrorsTheEngineSets() {
        java.util.Map<String, Object> schema = AdConfigValidator.schema();
        List<String> rewards = (List<String>) schema.get("rewards");
        assertEquals(Content.AdConfig.REWARDS.stream().sorted().toList(), rewards);
        assertEquals(List.of("cyber", "default", "retro"), (List<String>) schema.get("skins"),
                "面板能选的皮肤就这三张，且顺序稳定（Set 的迭代顺序不可依赖，所以说明书里必须排过序）");
        List<String> top = (List<String>) ((java.util.Map<String, Object>) schema.get("fields")).get("top");
        assertEquals(Content.AdConfig.class.getRecordComponents().length, top.size(),
                "顶层可写字段数应等于 AdConfig 的 record 组件数——加字段却忘了守卫会在这里红");
        for (var c : Content.AdConfig.class.getRecordComponents()) assertTrue(top.contains(c.getName()), "守卫漏了字段 " + c.getName());
    }

    /** 内置默认目录要能以 JSON 形态交给面板展开（slots 缺省≠空数组，见 AdConfigValidator.defaults 注释）。 */
    @Test
    void defaultsAreSerializableForTheEditor() throws IOException {
        JsonNode d = AdConfigValidator.defaults(om);
        assertEquals(Content.AdConfig.DEFAULT, om.treeToValue(d, Content.AdConfig.class),
                "展开给面板的默认目录必须和引擎里那份一模一样（序列化丢字段=面板显示的是另一套规则）");
        assertTrue(d.get("slots").isArray() && d.get("slots").size() > 0, "缺 slots 时面板无从展开");
        assertTrue(problems(d.toString()).isEmpty(), "面板展开的这份默认值本身必须是合法配置");
    }
}
