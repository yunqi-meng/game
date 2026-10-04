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
            Set.of(ConfigSpec.ENGINE, ConfigSpec.BOTH, ConfigSpec.PARTIAL, ConfigSpec.UI);

    private static Set<String> engineFields() {
        Set<String> out = new LinkedHashSet<>();
        for (var c : Content.Config.class.getRecordComponents()) out.add(c.getName());
        return out;
    }

    /** 种子里的 cfg_key：新增配置项却没写说明时，靠这个集合抓出来。 */
    private static Set<String> seededKeys() throws IOException {
        String sql = new String(new ClassPathResource("db/migration/V3__seed_config.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^\\('(\\w+)',", Pattern.MULTILINE).matcher(sql);
        while (m.find()) out.add(m.group(1));
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
        assertFalse(seeded.isEmpty(), "种子解析失败：正则没匹配到任何 cfg_key");
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
}
