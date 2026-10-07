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
 * 内容行解析失败不能再"静默丢"（followups 第 4 项）。
 *
 * <p><b>行为一字未改</b>：坏行仍然不进包、仍然不挡启动、仍然不发给玩家。这里钉的是"看得见的程度"——
 * 以前那一格是个空的 {@code catch (Exception ignored)}，症状是"玩家少了几个物质，后台一切正常"，
 * 只能一行一行猜。现在每次组装都留下两个读数（内容 / 配置各一档），供日志之外的口径核对。
 *
 * <p>刻意不去断言日志本身：日志行是给人看的，措辞改了不该红；<b>计数</b>才是这一层的契约面。
 */
class ContentServiceBadRowTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static ContentItem item(String type, String id, String json) {
        ContentItem it = new ContentItem();
        it.setContentType(type); it.setItemId(id); it.setData(json);
        return it;
    }

    private static ContentMapper contents(List<ContentItem> rows) {
        ContentMapper c = mock(ContentMapper.class);
        when(c.version()).thenReturn(7L);
        when(c.allEnabled()).thenReturn(rows);
        return c;
    }

    private static ConfigMapper configs(List<Map<String, Object>> rows) {
        ConfigMapper cfg = mock(ConfigMapper.class);
        when(cfg.allRaw()).thenReturn(rows);
        return cfg;
    }

    private static List<Map<String, Object>> okConfig() {
        return List.of(Map.of("cfg_key", "sell_rate", "cfg_value", "0.8"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rowsOf(Map<String, Object> bundle, String type) {
        Map<String, Object> content = (Map<String, Object>) bundle.get("content");
        return (List<Object>) content.get(type);
    }

    @Test
    @SuppressWarnings("unchecked")
    void badRowIsSkippedButCounted() {
        ContentMapper c = contents(List.of(
                item("element", "H", "{\"id\":\"H\",\"zh\":\"氢\"}"),
                item("element", "BROKEN", "{这不是 JSON"),
                item("element", "O", "{\"id\":\"O\",\"zh\":\"氧\"}")));
        ContentService svc = new ContentService(c, configs(okConfig()), OM);

        Map<String, Object> bundle = svc.bundle();
        // 下发照旧：两行好的在，坏的那行不在（既不是"整包失败"，也不是"把坏文本塞进去"）
        List<Object> elements = rowsOf(bundle, "element");
        assertEquals(2, elements.size(), "坏行必须继续被跳过——加了计数不等于改成抛异常");
        assertTrue(elements.stream().noneMatch(o -> "BROKEN".equals(((Map<String, Object>) o).get("id"))));
        assertEquals(1L, svc.badRows(), "静默丢必须留下一个可读的数，否则运维面还是瞎的");
        assertEquals(0L, svc.badConfigRows(), "内容坏不该算到配置头上");
    }

    @Test
    void cleanVersionReportsZero() {
        ContentService svc = new ContentService(contents(List.of(item("element", "H", "{\"id\":\"H\"}"))),
                configs(okConfig()), OM);
        svc.bundle();
        assertEquals(0L, svc.badRows());
        assertEquals(0L, svc.badConfigRows());
    }

    @Test
    @SuppressWarnings("unchecked")
    void badConfigRowIsCountedToo() {
        ContentService svc = new ContentService(contents(List.of(item("element", "H", "{\"id\":\"H\"}"))),
                configs(List.of(
                        Map.of("cfg_key", "sell_rate", "cfg_value", "0.8"),
                        Map.of("cfg_key", "quiz_reward", "cfg_value", "[1,2"))), OM);
        Map<String, Object> bundle = svc.bundle();

        assertEquals(0L, svc.badRows());
        assertEquals(1L, svc.badConfigRows());
        Map<String, Object> cfg = (Map<String, Object>) bundle.get("config");
        assertTrue(cfg.containsKey("sell_rate"));
        assertFalse(cfg.containsKey("quiz_reward"),
                "解析失败的配置不许以 null 的形式混进包里（引擎读到 null 比读不到更糟）");
    }

    @Test
    void cacheHoldsTheVerdictUntilInvalidate() {
        ContentMapper c = contents(List.of(item("element", "BROKEN", "{坏")));
        ContentService svc = new ContentService(c, configs(okConfig()), OM);

        Map<String, Object> first = svc.bundle();
        assertSame(first, svc.bundle(), "同一版反复读必须命中缓存，坏行不该被数第二遍");
        verify(c, times(1)).allEnabled();
        assertEquals(1L, svc.badRows());

        svc.invalidate();
        svc.bundle();
        verify(c, times(2)).allEnabled();
        assertEquals(1L, svc.badRows(), "重算之后坏行还在库里，计数不许被'成功组装'洗成 0");
    }
}
