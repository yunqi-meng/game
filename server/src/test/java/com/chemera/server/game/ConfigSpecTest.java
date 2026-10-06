package com.chemera.server.game;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置说明书的防漂移测试：说明必须与引擎真正读的字段一一对应。
 * 加字段不写说明、或写了个引擎不认的键，都在这里失败——这正是"标清楚"能被长期信任的前提。
 */
class ConfigSpecTest {

    private static final Set<String> SCOPES =
            Set.of(ConfigSpec.ENGINE, ConfigSpec.BOTH, ConfigSpec.PARTIAL, ConfigSpec.UI,
                    ConfigSpec.RETIRED, ConfigSpec.SERVER);

    private static Set<String> engineFields() {
        Set<String> out = new LinkedHashSet<>();
        for (var c : Content.Config.class.getRecordComponents()) out.add(c.getName());
        return out;
    }

    /**
     * 所有迁移里的 cfg_key：新增配置项却没写说明时，靠这个集合抓出来。
     * 刻意扫 V*.sql 全集而不是点文件名——V8 之后再加键（下一轮就是它）不必回来改这条测试，
     * 只补说明就行；漏补说明则由这条断言当场失败。
     *
     * <p>必须按 {@code INSERT INTO app_config} 语句切段再取元组首列：V2 的内容种子同样是
     * 行首 {@code ('element',…)} 的形状，全局正则会把 13 张内容表的表名一起当成 cfg_key。
     */
    private static Set<String> seededKeys() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        var res = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");
        Pattern stmt = Pattern.compile("INSERT\\s+INTO\\s+app_config\\b(.*?);",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern tuple = Pattern.compile("^\\('(\\w+)',", Pattern.MULTILINE);
        for (org.springframework.core.io.Resource r : res) {
            String sql = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher st = stmt.matcher(sql);
            while (st.find()) {
                Matcher m = tuple.matcher(st.group(1));
                while (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }

    @Test
    void specCoversExactlyWhatTheEngineModelHas() {
        Set<String> declared = new LinkedHashSet<>();
        for (ConfigSpec s : ConfigSpec.ALL) declared.add(s.key());
        assertEquals(engineFields(), declared,
                "ConfigSpec 必须与 Content.Config 的字段一一对应：多出来的是引擎不读的假键，缺了的是没写说明的新字段");
    }

    @Test
    void everySeededConfigKeyHasASpec() throws IOException {
        Set<String> declared = new LinkedHashSet<>();
        for (ConfigSpec s : ConfigSpec.ALL) declared.add(s.key());
        Set<String> seeded = seededKeys();
        // 解析自证：这几个键无论迁移怎么写都必然在库里。缺了不是"说明没补"，是正则没跟上——
        // 那会让这条防漂移测试安静地什么都不检查，比没有测试更糟。
        assertTrue(seeded.containsAll(Set.of("buy_rate", "quality", "ad", "curfew", "app_version", "analytics_enabled")),
                "种子解析失败，这些键必然在 app_config 里：" + seeded);
        List<String> missing = new ArrayList<>(seeded);
        missing.removeAll(declared);
        assertTrue(missing.isEmpty(), "这些配置项已入库但没有作用说明：" + missing);
    }

    @Test
    void eachSpecIsUsableAsOperatorCopy() {
        for (ConfigSpec s : ConfigSpec.ALL) {
            assertTrue(SCOPES.contains(s.scope()), s.key() + " 的 scope 不在四档之内：" + s.scope());
            assertFalse(s.zh().isBlank(), s.key() + " 缺中文名");
            assertTrue(s.effect().length() >= 15, s.key() + " 的作用说明太短，等于没说");
            assertFalse(s.note().isBlank(), s.key() + " 缺「改了会怎样」的提示");
            assertFalse(s.refs().isBlank(), s.key() + " 缺代码出处，说明无法核对");
        }
    }

    @Test
    void knobsThatDoNotAffectSettlementAreFlagged() {
        // 这两个是运营最容易误以为"调了就生效"的键，scope 降级成 UI/PARTIAL 是刻意的诚实标注
        assertEquals(ConfigSpec.UI, ConfigSpec.of("start_coins").scope(),
                "start_coins 目前只被前端初始档读取，服务端新建档写死 5000");
        assertEquals(ConfigSpec.UI, ConfigSpec.of("tier_names").scope());
        assertEquals(ConfigSpec.PARTIAL, ConfigSpec.of("lab_upgrades").scope(),
                "safety/bench 的实际效果常数写在引擎里，不能标成全生效");
    }

    @Test
    void complianceKeysAreLabelledAsServerBehaviourNotSettlement() {
        // 这三项动的是"让不让人进、旧包能不能用、留不留记录"，一分钱都不算。
        // 标成"服务端结算"会让运营以为调它能改经济，标成 UI 又会让人以为改了不生效——只有 SERVER 档是诚实的。
        assertEquals(ConfigSpec.SERVER, ConfigSpec.of("curfew").scope());
        assertEquals(ConfigSpec.SERVER, ConfigSpec.of("app_version").scope());
        assertEquals(ConfigSpec.SERVER, ConfigSpec.of("analytics_enabled").scope(),
                "埋点开关关停不会报错、只会让看板停止增长，必须在面板上跟结算项区分开");
        // 反过来，真正决定发什么的目录必须留在结算档
        assertEquals(ConfigSpec.ENGINE, ConfigSpec.of("ad").scope());
    }

    @Test
    void retiredKeysAreLabelledAndHaveNoSettlementCode() {
        // 付费面下线后留在库里的历史键：必须显式标成退役，运营才不会以为改了它还能收到钱
        assertEquals(ConfigSpec.RETIRED, ConfigSpec.of("recharge").scope(),
                "recharge 已随 shop.recharge 一起回绝，只有退役标注是诚实的说法");
        // 光靠文案不算数：真正发钱的那两个方法必须已经从经济服务里删掉
        assertTrue(java.util.Arrays.stream(EconomyService.class.getDeclaredMethods())
                        .noneMatch(m -> m.getName().equals("recharge") || m.getName().equals("buyDiamondItem")),
                "EconomyService 仍留有付费/钻石商店的结算方法，那 recharge 就不是退役键");
    }

    /* ---------------- H6：编辑器形态与风险色阶也只在这里有一份 ---------------- */

    @Test
    void formIdsComeFromTheClosedSetAndEveryDeclaredFormIsUsed() {
        Set<String> used = new LinkedHashSet<>();
        for (ConfigSpec s : ConfigSpec.ALL) {
            String kind = s.formKind();
            assertTrue(ConfigSpec.FORMS.contains(kind),
                    s.key() + " 的编辑器形态「" + kind + "」不在 FORMS 闭合集合里：面板认不出它，会安静地退回 JSON 文本框");
            if ("number".equals(kind))
                assertTrue(s.form().matches("number:[0-9]+(\\.[0-9]+)?:[0-9]+"),
                        s.key() + " 的 number 形态要写成 number:步进:小数位（面板只做 Number()，写成 \"number\" 就是 NaN），现在是 " + s.form());
            used.add(kind);
        }
        Set<String> unused = new LinkedHashSet<>(ConfigSpec.FORMS);
        unused.removeAll(used);
        assertTrue(unused.isEmpty(),
                "FORMS 里这些形态没有任何配置键在用，面板对应的分支就是死代码（要么补键、要么把形态和分支一起删）：" + unused);
    }

    @Test
    void toneIsDerivedFromScopeAndNeverHandWritten() {
        for (ConfigSpec s : ConfigSpec.ALL)
            assertEquals(ConfigSpec.toneOf(s.scope()), s.tone(),
                    s.key() + " 的 tone 与 scope 不匹配：tone 只能由 scope 推导，自己填就是第二份答案");
        // 「改它什么都不发生」和「改它不影响结算」是两件事。同色就等于告诉运营这两行可以随手拨。
        assertNotEquals(ConfigSpec.of("recharge").tone(), ConfigSpec.of("start_coins").tone(),
                "已退役键与「仅前端」同色，上一轮的错位会原地复发");
        // 最响的那一档必须留给真正会动到全体玩家钱包的键（外提这批之后 level_exp 也是）
        assertEquals(ConfigSpec.TONE_DANGER, ConfigSpec.of("sell_rate").tone());
        assertEquals(ConfigSpec.TONE_DANGER, ConfigSpec.of("accident").tone());
        // 合规闸门不能和经济风险同色：调错它的后果是"人被关在门外"，不是"经济崩盘"
        assertEquals(ConfigSpec.TONE_GUARD, ConfigSpec.of("curfew").tone());
        assertEquals(ConfigSpec.TONE_GUARD, ConfigSpec.of("analytics_enabled").tone());
    }

    @Test
    void unknownScopeFailsLoudInsteadOfQuietlyGoingGrey() {
        // 这条是上面那张"scope → 色阶"switch 的全部意义：加一档 scope 而没定颜色，
        // 必须当场炸（后台整页打不开，一定有人问），而不是安静地用最温和的那档。
        assertThrows(IllegalArgumentException.class, () -> ConfigSpec.toneOf("服务端结算（新）"),
                "toneOf 对不认识的 scope 给了默认值，那新一档会显示成兜底色而没人发现");
        Set<String> inUse = new LinkedHashSet<>();
        for (ConfigSpec s : ConfigSpec.ALL) inUse.add(s.scope());
        assertEquals(SCOPES, inUse,
                "scope 档位与 ConfigSpec 实际用到的必须一一对应：多了是文档里的空档，少了是有人删了一档却没删声明");
    }
}
