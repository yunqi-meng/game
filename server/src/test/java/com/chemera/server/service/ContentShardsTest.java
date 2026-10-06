package com.chemera.server.service;

import com.chemera.server.entity.ContentItem;
import com.chemera.server.mapper.ConfigMapper;
import com.chemera.server.mapper.ContentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 内容分片体检接口（C3 留下的那把尺子）。
 *
 * <p>它存在的理由只有一个：下次有人提"bundle 再按类型拆一片吧"，先在这里读一眼数字，
 * 而不是凭感觉动启动链。所以断言的重点不是"能列出来"，而是"数字真的能用来做决定"——
 * 排序按体积降序、坏行被跳过时计数会掉（不然体检报告看着健康）、总数与明细对得上。
 */
class ContentShardsTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static ContentItem item(String type, String json) {
        ContentItem it = new ContentItem();
        it.setContentType(type); it.setData(json);
        return it;
    }

    private static ContentService svc(ContentMapper c, ConfigMapper cfg) {
        return new ContentService(c, cfg, OM);
    }

    private static ContentMapper contents(List<ContentItem> rows, long version) {
        ContentMapper c = mock(ContentMapper.class);
        when(c.version()).thenReturn(version);
        when(c.allEnabled()).thenReturn(rows);
        return c;
    }

    private static ConfigMapper configs() {
        ConfigMapper cfg = mock(ConfigMapper.class);
        when(cfg.allRaw()).thenReturn(List.of(
                Map.of("cfg_key", "sell_rate", "cfg_value", "0.8"),
                Map.of("cfg_key", "start_coins", "cfg_value", "5000")));
        return cfg;
    }

    @Test
    @SuppressWarnings("unchecked")
    void shardsRankBySizeAndAddUp() {
        ContentMapper c = contents(List.of(
                item("reaction", "{\"id\":\"R001\",\"eq\":\"2H2 + O2 → 2H2O\",\"phenomenon\":\"淡蓝色火焰，放出大量热\"}"),
                item("reaction", "{\"id\":\"R002\",\"eq\":\"Na + Cl2 → NaCl\"}"),
                item("element", "{\"id\":\"H\",\"zh\":\"氢\",\"desc\":\"宇宙中最丰富的轻元素\"}")), 12L);
        Map<String, Object> rep = svc(c, configs()).shards();

        assertEquals(12L, rep.get("version"));
        List<Map<String, Object>> shards = (List<Map<String, Object>>) rep.get("shards");
        assertTrue(shards.size() >= 3, "两个内容类型 + config 至少三行");
        // 体积降序：方程式那一片必须排在元素前面，否则这份报告没法用来决定"先动谁"
        long first = ((Number) shards.get(0).get("bytes")).longValue();
        long last = ((Number) shards.get(shards.size() - 1).get("bytes")).longValue();
        assertTrue(first >= last, "未按体积降序：" + shards);
        assertEquals("reaction", shards.get(0).get("type"));
        assertEquals(2, shards.get(0).get("count"));

        long sum = shards.stream().mapToLong(m -> ((Number) m.get("bytes")).longValue()).sum();
        assertEquals(((Number) rep.get("bytes")).longValue(), sum, "总数与明细对不上，报告就成了两个版本");
    }

    /** 坏 JSON 行会被 bundle() 静默跳过（客户端拿不到它），体检必须把这个"少了"如实报出来。 */
    @Test
    @SuppressWarnings("unchecked")
    void brokenRowShowsUpAsMissingCount() {
        ContentMapper c = contents(List.of(
                item("quiz", "{\"id\":\"q1\",\"q\":\"问题\",\"opts\":[\"a\",\"b\"]}"),
                item("quiz", "{这不是 JSON")), 3L);
        List<Map<String, Object>> shards =
                (List<Map<String, Object>>) svc(c, configs()).shards().get("shards");
        Map<String, Object> quiz = shards.stream().filter(m -> "quiz".equals(m.get("type"))).findFirst().orElseThrow();
        assertEquals(1, quiz.get("count"), "库里两行、下发一行：count 要跟着掉，不然健康检查看着像全绿");
    }

    /** 报告跟着 bundle 的缓存版本走：invalidate 之后重新数一遍（后台刚改完内容就该看到新数字）。 */
    @Test
    void reportTracksContentVersion() {
        ContentMapper c = contents(List.of(item("element", "{\"id\":\"H\",\"zh\":\"氢\"}")), 21L);
        ContentService s = svc(c, configs());
        assertEquals(21L, s.shards().get("version"));
        s.invalidate();
        assertEquals(21L, s.shards().get("version"));
        verify(c, atLeast(2)).version();
    }

    /** config 不是内容表，但它随包下发、也占体积：漏报就等于给"还能省多少"留了个盲区。 */
    @Test
    @SuppressWarnings("unchecked")
    void configIsItsOwnShard() {
        ContentMapper c = contents(List.of(item("element", "{\"id\":\"H\",\"zh\":\"氢\"}")), 1L);
        List<Map<String, Object>> shards =
                (List<Map<String, Object>>) svc(c, configs()).shards().get("shards");
        Map<String, Object> cfg = shards.stream().filter(m -> "config".equals(m.get("type"))).findFirst().orElseThrow();
        assertEquals(2, cfg.get("count"));
        assertTrue(((Number) cfg.get("bytes")).longValue() > 0);
    }
}
