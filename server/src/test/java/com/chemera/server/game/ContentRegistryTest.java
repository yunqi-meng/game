package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContentRegistry 解析回归（纯单元测试，读取导出的真实 bundle 快照 content-bundle.json，不连数据库）。
 * 断言各内容类型条数与关键字段被正确强类型化，以及配置常量归一。
 */
class ContentRegistryTest {

    private final ObjectMapper om = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private ContentRegistry.Snapshot loadSnapshot() throws Exception {
        Map<String, Object> bundle;
        try (InputStream in = getClass().getResourceAsStream("/content-bundle.json")) {
            assertNotNull(in, "测试快照 content-bundle.json 缺失");
            bundle = om.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        long v = ((Number) bundle.getOrDefault("version", 0)).longValue();
        Map<String, Object> content = (Map<String, Object>) bundle.getOrDefault("content", Map.of());
        Map<String, Object> config = (Map<String, Object>) bundle.getOrDefault("config", Map.of());
        // build 只依赖 ObjectMapper，不触碰 ContentService，故可传 null
        return new ContentRegistry(null, om).build(v, content, config);
    }

    @Test
    void parsesAllContentTypes() throws Exception {
        ContentRegistry.Snapshot s = loadSnapshot();
        assertEquals(143, s.reactions.size());
        assertEquals(118, s.elements.size());
        assertEquals(96, s.compounds.size());
        assertEquals(3, s.consumables.size());
        assertEquals(27, s.instruments.size());
        assertEquals(5, s.rooms.size());
        assertEquals(3, s.processes.size());
        assertEquals(5, s.dangers.size());
        assertEquals(19, s.achievements.size());
        assertEquals(4, s.tasks.size());
        assertEquals(4, s.npcs.size());
        assertEquals(6, s.shop.size());
        assertEquals(25, s.quizzes.size());
    }

    @Test
    void typedReactionFields() throws Exception {
        ContentRegistry.Snapshot s = loadSnapshot();
        Content.Reaction r010 = s.reaction("R010");
        assertNotNull(r010);
        assertEquals(Map.of("C", 2, "O2", 1), r010.reactantsOr());
        assertEquals(Map.of("CO", 2), r010.productsOr());
        assertEquals("ignite", r010.cond().tempOrRoom());
        assertFalse(r010.cond().elec());
        assertNull(r010.cond().catalyst());
        assertEquals(List.of("crucible"), r010.instrumentOr());
        assertEquals(5, r010.lv());
        assertEquals(25, r010.expOr());

        // 催化 + 电解条件分别可类型化
        Content.Reaction r001 = s.reaction("R001");
        assertEquals("MnO2", r001.cond().catalyst());
        assertEquals("heat", r001.cond().tempOrRoom());
        Content.Reaction r007 = s.reaction("R007");
        assertTrue(r007.cond().elec());
        // R010 与 R009 是同反应物族但配比不同——两者都应在表中
        assertNotNull(s.reaction("R009"));
    }

    @Test
    void normalizedSubstanceView() throws Exception {
        ContentRegistry.Snapshot s = loadSnapshot();
        Content.Substance h = s.substance("H");
        assertEquals("element", h.kind());
        assertEquals("H", h.formula());   // 元素 formula 取 symbol
        assertEquals(1, h.z());
        assertEquals(10, h.price());

        Content.Substance na = s.substance("Na");
        assertTrue(na.hazard(), "碱金属 hazard 应归一为 true");

        Content.Substance slag = s.substance("SLAG");
        assertNotNull(slag);
        assertEquals(5, slag.price());

        Content.Substance fp = s.substance("filterpaper");
        assertEquals("consumable", fp.kind());

        // 化合物：level/elements 保留
        Content.Substance dia = s.substance("diamond");
        assertNotNull(dia);
        assertEquals("compound", dia.kind());
        assertEquals(4, dia.level());
        assertEquals(9500, dia.price());
    }

    @Test
    void instrumentRoomAndConfig() throws Exception {
        ContentRegistry.Snapshot s = loadSnapshot();
        assertTrue(s.instrument("cylinder").noHeatOr());
        assertEquals(5, s.instrument("volumetric").batch());
        assertEquals(0.05, s.instrument("spotplate").yield(), 1e-9);
        assertTrue(s.instrument("lamp").isVessel() == false, "lamp 是 equipment 非 vessel");

        assertTrue(s.room("hiprecision").typesOr().contains("有机反应"));
        assertEquals("organic", s.rooms.get(3).id());

        Content.Config cfg = s.config;
        assertEquals(0.8, cfg.sellRate(), 1e-9);
        assertEquals(1.2, cfg.buyRate(), 1e-9);
        assertEquals(1.5, cfg.firstSellBonus(), 1e-9);
        assertEquals(5000L, cfg.startCoins());
        assertEquals(120L, cfg.quizReward());
        assertEquals(1.5, cfg.qualityMult(2), 1e-9);
        assertArrayEquals(new int[]{3000, 10000}, cfg.discoverBonus(4));
        assertEquals(50, cfg.lab("storage").step());
        assertTrue(cfg.milestonesOr().contains(20));
        assertEquals(3, cfg.tierUpCost(0));
        assertEquals(3, cfg.rechargeOr().size());
    }
}
