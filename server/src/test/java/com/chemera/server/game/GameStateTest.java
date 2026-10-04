package com.chemera.server.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** GameState 与客户端存档 JSON 的双向兼容回归（纯单元测试，不需要数据库）。 */
class GameStateTest {

    private final ObjectMapper om = new ObjectMapper();

    /** 形态取自 js/state.js defaults()（START_COINS=5000），并掺入未知字段验证 ignoreUnknown。 */
    private static final String SAMPLE_SAVE = "{\n" +
            "  \"v\": 2,\n" +
            "  \"coins\": 5000, \"diamonds\": 3, \"exp\": 120, \"level\": 2,\n" +
            "  \"bag\": { \"H2|0\": 8, \"O2|0\": 6, \"H2O|1\": 2 },\n" +
            "  \"discovered\": { \"H2O\": { \"times\": 4, \"first\": 1700000000000 } },\n" +
            "  \"reactionsKnown\": { \"R010\": true },\n" +
            "  \"firstBonusTaken\": { \"Cu\": true },\n" +
            "  \"vessels\": { \"testtube\": {\"owned\":true,\"tier\":0}, \"beaker\": {\"owned\":true,\"tier\":2} },\n" +
            "  \"equipment\": { \"lamp\": true, \"dropper\": true },\n" +
            "  \"lab\": { \"storage\": 1, \"safety\": 2, \"bench\": 0 },\n" +
            "  \"rooms\": [\"inorganic\",\"analysis\"], \"bi\": 1,\n" +
            "  \"benchStates\": [ {\"vessel\":\"beaker\",\"placed\":{\"H2\":2,\"O2\":1},\"temp\":\"ignite\",\"electrolysis\":false} ],\n" +
            "  \"stats\": { \"success\": 7, \"boom\": 1, \"trades\": 3 },\n" +
            "  \"sign\": { \"last\": \"2026-09-24\", \"streak\": 2 },\n" +
            "  \"listings\": [ {\"id\":\"Cu\",\"q\":1,\"n\":10,\"price\":250,\"mat\":1700000000000.5} ],\n" +
            "  \"chal\": { \"reactId\":\"R010\",\"target\":\"CO\",\"given\":{\"C\":4,\"O2\":2},\"decoys\":{\"Au\":2},\"steps\":1,\"max\":4,\"win\":false,\"reward\":950 },\n" +
            "  \"realMode\": true, \"volSfx\": 55, \"volMus\": 20, \"music\": true,\n" +
            "  \"insured\": true, \"hints\": 2, \"rep\": 25,\n" +
            "  \"cloud\": { \"auto\": true, \"lastSync\": 1 },\n" +
            "  \"muted\": false, \"someFutureField\": {\"x\":1}\n" +
            "}";

    @Test
    void parsesClientShapedSave() throws Exception {
        GameState g = om.readValue(SAMPLE_SAVE, GameState.class);
        assertEquals(2, g.getV());
        assertEquals(5000L, g.getCoins());
        assertEquals(2, g.getLevel());
        assertEquals(8, g.getBag().get("H2|0"));
        assertEquals(2, g.getBag().get("H2O|1"));
        assertEquals(4, g.getDiscovered().get("H2O").getTimes());
        assertEquals(Boolean.TRUE, g.getReactionsKnown().get("R010"));
        assertTrue(g.getVessels().get("beaker").isOwned());
        assertEquals(2, g.getVessels().get("beaker").getTier());
        assertEquals(2, g.getLab().getSafety());
        assertEquals(2, g.getRooms().size());
        assertEquals(1, g.getBi());
        // benchStates 回填
        assertNotNull(g.getBenchStates());
        assertEquals(1, g.getBenchStates().size());
        assertEquals("ignite", g.getBenchStates().get(0).getTemp());
        assertEquals(2, g.getBenchStates().get(0).getPlaced().get("H2"));
        // 挂单浮点 mat、挑战嵌套、设置项
        assertEquals(1700000000000.5, g.getListings().get(0).getMat(), 1e-6);
        assertEquals("CO", g.getChal().getTarget());
        assertEquals(4, g.getChal().getGiven().get("C"));
        assertTrue(g.isRealMode());
        assertEquals(55, g.getVolSfx());
        assertTrue(g.isInsured());
        assertEquals(25, g.getRep());
        // 部分 stats 字段回填、未知字段被忽略而非报错
        assertEquals(7, g.getStats().getSuccess());
        assertEquals(1, g.getStats().getBoom());
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        GameState g = om.readValue(SAMPLE_SAVE, GameState.class);
        String json = om.writeValueAsString(g);
        GameState again = om.readValue(json, GameState.class);
        assertEquals(om.writeValueAsString(g), json, "二次序列化应稳定");
        assertEquals(g.getCoins(), again.getCoins());
        assertEquals(g.getBag().get("H2O|1"), again.getBag().get("H2O|1"));
        assertEquals(g.getVessels().get("beaker").getTier(), again.getVessels().get("beaker").getTier());
        assertEquals(g.getBenchStates().get(0).getTemp(), again.getBenchStates().get(0).getTemp());
        assertEquals(g.getChal().getDecoys().get("Au"), again.getChal().getDecoys().get("Au"));
    }

    @Test
    void freshMatchesClientDefaults() {
        GameState g = GameState.fresh(1700000000000L);
        assertEquals(5000L, g.getCoins());
        assertEquals(1, g.getLevel());
        assertEquals(8, g.getBag().get("H2|0"));
        assertEquals(3, g.getBag().get("NaOH|0"));
        assertTrue(g.getVessels().get("testtube").isOwned());
        assertEquals(Boolean.TRUE, g.getEquipment().get("lamp"));
        assertEquals(1, g.getRooms().size());
        assertEquals("2023-11-14", g.getDaily().getDate()); // UTC dayKey of that epoch
    }

    @Test
    void tolerantOfEmptyObject() throws Exception {
        GameState g = om.readValue("{}", GameState.class);
        // 缺省字段用初始值（bag 空、level=1），不抛异常
        assertEquals(1, g.getLevel());
        assertTrue(g.getBag().isEmpty());
        Map<String, Object> unused = Map.of();
        assertFalse(unused.containsKey("x"));
    }
}
