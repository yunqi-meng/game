package com.chemera.server.game;

import com.chemera.server.common.BizException;
import com.chemera.server.mapper.ModerationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 敏感词分级（G2）的那把尺子。
 *
 * <p>{@code sensitive_word.level} 从建表起就写着 1=提示 / 2=拦截，而 {@code AuthService} 过去的做法是
 * 一律拒绝——字段存在却不生效，比没有这个字段更糟：运营在后台把某个词调成"提示"，玩家照样被拒。
 * 所以这里最要紧的一条不是"能匹配"，而是<b>同一份词表里两档词走出两种结果</b>：
 * 2 档抛、1 档放行但把命中的词回给调用方。
 *
 * <p>词表缓存也在这里钉：以前每次注册都整表 {@code SELECT} 一遍，把一张几乎不变的小表当热路径查询打。
 * Mapper 全 mock，正因为要数的就是"这一次到底查没查库"。
 */
class SensitiveFilterTest {

    private static ModerationMapper table(List<Map<String, Object>> rows) {
        ModerationMapper m = mock(ModerationMapper.class);
        when(m.words()).thenReturn(rows);
        return m;
    }

    private static Map<String, Object> row(String word, int level) {
        return Map.of("word", word, "level", level);
    }

    /** 一条 2 档 + 两条 1 档：拦截优先，但提示档的命中一个都不能漏。 */
    @Test
    void levelTwoBlocksWhileLevelOneIsOnlyNoted() {
        ModerationMapper m = table(List.of(row("脏话", 2), row("代练", 1), row("外挂", 1)));
        SensitiveFilter f = new SensitiveFilter(m, 60);

        SensitiveFilter.Verdict v = f.scan("你这脏话，还卖代练");
        assertEquals("脏话", v.blocked());
        assertTrue(v.rejected());
        assertEquals(List.of("代练"), v.flagged(), "命中词要回给调用方，昵称那条路靠它决定'提示'的文案");
        assertTrue(v.noted());
    }

    @Test
    void hintLevelPassesButStillReportsTheWord() {
        SensitiveFilter f = new SensitiveFilter(table(List.of(row("代练", 1))), 60);
        SensitiveFilter.Verdict v = f.scan("专业代练上分");
        assertFalse(v.rejected(), "1 档不该把写入打死：那是运营自己调的级别");
        assertEquals(List.of("代练"), v.flagged());
        assertDoesNotThrow(() -> f.block("专业代练上分"));
    }

    @Test
    void blockThrowsOnlyForTheInterceptLevel() {
        SensitiveFilter f = new SensitiveFilter(table(List.of(row("脏话", 2))), 60);
        BizException e = assertThrows(BizException.class, () -> f.block("这句有脏话"));
        assertTrue(e.getMessage().contains("敏感词"), "文案要给玩家下一步，而不是'非法字符'：" + e.getMessage());
        assertDoesNotThrow(() -> f.block("干净的昵称"));
    }

    /** 缺 level（后台旧数据／手工插入）按提示档处理：宁可放过也不静默打死一个昵称。 */
    @Test
    void aRowWithoutALevelIsTreatedAsHint() {
        ModerationMapper m = mock(ModerationMapper.class);
        when(m.words()).thenReturn(List.of(Map.of("word", "老王")));
        SensitiveFilter f = new SensitiveFilter(m, 60);
        SensitiveFilter.Verdict v = f.scan("老王邻居");
        assertFalse(v.rejected());
        assertEquals(List.of("老王"), v.flagged());
    }

    @Test
    void emptyInputNeverAsksTheDatabase() {
        ModerationMapper m = table(List.of(row("脏话", 2)));
        SensitiveFilter f = new SensitiveFilter(m, 60);
        assertFalse(f.scan(null).rejected());
        assertFalse(f.scan("   ").noted());
        verify(m, never()).words();
    }

    /* ---------------- 缓存：这张小表不该每次都问 ---------------- */

    /**
     * TTL 之内只查一次库。
     *
     * <p>"注册一次查一遍整表"是以前那条热路径的长相；缓存换到的确定性是
     * "后台刚加的词最坏 60 秒后才生效"——而 {@link SensitiveFilter#invalidate()} 走同进程直接调用，
     * 所以后台加词其实立刻生效，TTL 只兜"多实例时另一台没被叫到"这条未来路径。
     */
    @Test
    void tableIsCachedInsideTheTtlAndReLoadedAfterInvalidate() {
        ModerationMapper m = table(List.of(row("脏话", 2)));
        SensitiveFilter f = new SensitiveFilter(m, 60);

        assertTrue(f.scan("有脏话").rejected());
        assertTrue(f.scan("又有脏话").rejected());
        assertTrue(f.scan("还是脏话").rejected());
        verify(m, times(1)).words();
        assertEquals(1, f.cachedSize());

        f.invalidate();
        assertFalse(f.scan("换个词").noted());
        verify(m, times(2)).words();
    }

    /** TTL 设成 0 就是"每次都读"，这是给"词表必须立刻全网生效"那种部署留的口子。 */
    @Test
    void zeroTtlMeansNoCaching() {
        ModerationMapper m = table(List.of(row("脏话", 2)));
        SensitiveFilter f = new SensitiveFilter(m, 0);
        f.scan("a");
        f.scan("b");
        verify(m, times(2)).words();
    }

    /**
     * 读词表失败不能把注册接口拖下水。
     *
     * <p>这条是"降级方向"的钉子：库抖一下时，宁可按上一份词表判断（甚至没有词表就放行），
     * 也不能让一次 {@code MySQLTimeout} 变成玩家看到的"注册失败"。
     */
    @Test
    void aFailedReloadFallsBackToTheLastKnownTable() {
        ModerationMapper m = mock(ModerationMapper.class);
        when(m.words()).thenReturn(List.of(row("脏话", 2)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(List.of(row("脏话", 2)));
        SensitiveFilter f = new SensitiveFilter(m, 0);

        assertTrue(f.scan("第一句有脏话").rejected());          // 建立第一份缓存
        assertTrue(f.scan("第二句还有脏话").rejected(), "读库失败要沿用上一次的词表，而不是放行");
        verify(m, times(2)).words();
    }

    /** 词表为空时任何文本都放行：这是"运营删光了词"的正常结果，不是 bug。 */
    @Test
    void anEmptyTableLetsEverythingThrough() {
        SensitiveFilter f = new SensitiveFilter(table(List.of()), 60);
        assertFalse(f.scan("任意文本，包括脏话").rejected());
        assertDoesNotThrow(() -> f.block("任意文本"));
    }
}
