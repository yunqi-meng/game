package com.chemera.server.service;

import com.chemera.server.mapper.AdTicketMapper;
import com.chemera.server.mapper.AnalyticsMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 日报聚合与老数据清理（G1）。
 *
 * <p>{@code daily_stats} 从 V1 就建好了、看板也在读它，但<b>没有任何人往里写</b>——恒 0 的面板
 * 比没有面板更糟，运营会读成"今天没人玩"。同一处还有两张只增不减的表：
 * {@code analytics_event.purgeOld} 与 {@code ad_ticket.purgeOld} 都写好了却零调用者。
 * 这个文件要钉的就是"写的人终于存在了，而且写的口径不会自己骗自己"。
 *
 * <p>Mapper 全 mock：这里判的是调用形状（算哪天、传不传对列、清多少天），
 * 真库上那条聚合 SQL 的正确性由后台【重算】按钮 + e2e 覆盖。
 */
class DailyStatsJobTest {

    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    private static AnalyticsMapper analytics(Map<String, Object> row) {
        AnalyticsMapper a = mock(AnalyticsMapper.class);
        when(a.statRow(any())).thenReturn(row);
        return a;
    }

    private static Map<String, Object> row(int dau, int nu, int rx, int bm, int tr) {
        return Map.of("dau", dau, "nu", nu, "rx", rx, "bm", bm, "tr", tr);
    }

    /* ---------------- 1. 落库的列要对得上 ---------------- */

    @Test
    void rollsOneDayIntoTheFiveColumnsTheBoardReads() {
        AnalyticsMapper a = analytics(row(120, 9, 400, 31, 57));
        AdTicketMapper t = mock(AdTicketMapper.class);
        DailyStatsJob job = new DailyStatsJob(a, t, 180, 90);

        Map<String, Object> out = job.roll(YESTERDAY);

        verify(a).upsertDaily(YESTERDAY, 120, 9, 400, 31, 57);
        assertEquals(YESTERDAY.toString(), out.get("day"));
        assertEquals(120, out.get("dau"));
        assertEquals(9, out.get("newUsers"));
        assertEquals(400, out.get("reactions"));
        assertEquals(31, out.get("booms"));
        assertEquals(57, out.get("trades"));
    }

    /**
     * 列名必须是没有下划线的短别名。
     *
     * <p>{@code map-underscore-to-camel-case} 只作用于实体类，<b>不作用于 Map 的键</b>：
     * 聚合查询里一旦写 {@code new_users}，{@code row.get("nu")} 就永远拿到 null，
     * 于是这一列静默变成 0——面板上又是一块"看起来健康"的假绿。所以这里连 SQL 注解一起钉住。
     */
    @Test
    void theAggregateAliasesStayUnderscoreFree() throws Exception {
        String sql = String.join(" ", AnalyticsMapper.class
                .getMethod("statRow", java.time.LocalDate.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        // 这五个键就是 DailyStatsJob.num() 读的那五个；少一个或改名，那一列会静默算成 0
        assertTrue(sql.contains(") dau,") && sql.contains(") nu,")
                        && sql.contains(") rx,") && sql.contains(") bm,") && sql.contains(") tr"),
                "五个汇总各占一个短别名：" + sql);
        assertFalse(sql.contains("new_users") || sql.contains("react_boom bm_"),
                "别让实体口径那种带下划线的别名混进这张 Map 查询：" + sql);
    }

    /** 库里那天什么也没有：写一行全 0，而不是抛或干脆不写——"那天真的没人玩"也是结论。 */
    @Test
    void anEmptyDayWritesZeroesRatherThanNothing() {
        AnalyticsMapper a = analytics(Map.of());
        DailyStatsJob job = new DailyStatsJob(a, mock(AdTicketMapper.class), 180, 90);

        Map<String, Object> out = job.roll(LocalDate.of(2020, 1, 1));

        verify(a).upsertDaily(LocalDate.of(2020, 1, 1), 0, 0, 0, 0, 0);
        assertEquals(0, out.get("dau"));
    }

    /* ---------------- 2. 结清的是昨天，顺带补前天 ---------------- */

    /**
     * 为什么不是"今天"：今天那两个数（{@code last_login_at}／{@code created_at}）还在动，
     * 此刻写进去的一行到明天就是错的，而且再也不会被重算。
     */
    @Test
    void sweepSettlesYesterdayAndBackfillsTheDayBefore() {
        AnalyticsMapper a = analytics(row(1, 1, 1, 1, 1));
        AdTicketMapper t = mock(AdTicketMapper.class);
        DailyStatsJob job = new DailyStatsJob(a, t, 180, 90);

        job.sweep();

        ArgumentCaptor<LocalDate> days = ArgumentCaptor.forClass(LocalDate.class);
        verify(a, times(2)).upsertDaily(days.capture(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        LocalDate today = LocalDate.now();
        assertEquals(today.minusDays(1), days.getAllValues().get(0), "先结清昨天");
        assertEquals(today.minusDays(2), days.getAllValues().get(1), "再补一次前天：跨零点那几分钟的事件要归位");
    }

    /* ---------------- 3. 清理真的在跑，天数不会被配成"明天全删光" ---------------- */

    @Test
    void sweepPurgesBothTablesWithTheConfiguredRetention() {
        AnalyticsMapper a = analytics(row(0, 0, 0, 0, 0));
        when(a.purgeOld(200)).thenReturn(4000);
        AdTicketMapper t = mock(AdTicketMapper.class);
        when(t.purgeOld(90)).thenReturn(12);

        new DailyStatsJob(a, t, 200, 90).sweep();

        verify(a).purgeOld(200);
        verify(t).purgeOld(90);
    }

    /**
     * 保留天数低于 7 天一律抬到 7。
     *
     * <p>这不是洁癖：配错一个数（把 180 写成 1）会让埋点表和广告工单在下次凌晨被删到只剩一天，
     * 而那种删除<b>不可恢复</b>，等到看板空了才发现。宁可多留一点磁盘。
     */
    @Test
    void retentionNeverDropsBelowAWeek() {
        AnalyticsMapper a = analytics(row(0, 0, 0, 0, 0));
        AdTicketMapper t = mock(AdTicketMapper.class);

        new DailyStatsJob(a, t, 1, 0).sweep();
        verify(a).purgeOld(7);
        verify(t).purgeOld(7);

        AnalyticsMapper neg = analytics(row(0, 0, 0, 0, 0));
        AdTicketMapper negT = mock(AdTicketMapper.class);
        new DailyStatsJob(neg, negT, -5, -1).sweep();
        verify(neg).purgeOld(7);
        verify(negT).purgeOld(7);
    }

    /* ---------------- 4. 四步各自独立：一行抛错不许把后面全带走 ---------------- */

    /**
     * 昨天那一步炸了，前天照落、两张表的清理照跑，而且整趟不往外抛。
     *
     * <p>这四步互相没有关系，没有理由一起死。修之前的形状是"一串顺序调用外面什么都不包"，
     * 于是最现实的落点就是：{@code statRow} 的聚合 SQL 超时（埋点表长大之后这是常态），
     * 一句话把前天补算和两次 {@code purgeOld} 全部带走，日志里只看得见第一句栈——
     * 表继续只增不减，而看板上一片"看起来正常"的空值。
     */
    @Test
    void oneFailingStepNeverTakesTheRestDown() {
        AnalyticsMapper a = mock(AnalyticsMapper.class);
        LocalDate today = LocalDate.now();
        when(a.statRow(any())).thenReturn(row(1, 1, 1, 1, 1));                    // 先给个通用桩
        when(a.statRow(today.minusDays(1))).thenThrow(new RuntimeException("聚合 SQL 超时"));
        when(a.purgeOld(180)).thenReturn(4000);
        AdTicketMapper t = mock(AdTicketMapper.class);
        when(t.purgeOld(90)).thenReturn(12);

        DailyStatsJob job = new DailyStatsJob(a, t, 180, 90);
        assertDoesNotThrow(job::sweep, "定时任务不该把一次失败升级成整趟没跑，更不该往外抛给调度线程");

        verify(a).upsertDaily(today.minusDays(2), 1, 1, 1, 1, 1);                 // 前天补算照做
        verify(a).purgeOld(180);                                                   // 两张表也照清
        verify(t).purgeOld(90);
    }

    /** 清理那一步抛错也一样：一张表删不动，不连累另一张，更不连累已经跑过的聚合。 */
    @Test
    void aFailingPurgeDoesNotSwallowTheOtherOne() {
        AnalyticsMapper a = analytics(row(2, 0, 0, 0, 0));
        when(a.purgeOld(anyInt())).thenThrow(new RuntimeException("DELETE 锁等待超时"));
        AdTicketMapper t = mock(AdTicketMapper.class);

        new DailyStatsJob(a, t, 180, 90).sweep();

        verify(t).purgeOld(90);
        verify(a, times(2)).upsertDaily(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 逐步捕获只属于定时任务这条路：后台【重算】按钮是人工触发的，失败必须原样报错。
     *
     * <p>如果顺手把 {@code roll} 也包成"吞掉异常回一个空行"，运营点了重算看见的是"没数据"，
     * 而不是"这次重算失败了"——那正好是 G1 立项要治的那种假绿。
     */
    @Test
    void aManualRollStillSurfacesItsError() {
        AnalyticsMapper a = mock(AnalyticsMapper.class);
        when(a.statRow(any())).thenThrow(new RuntimeException("库里断了"));
        DailyStatsJob job = new DailyStatsJob(a, mock(AdTicketMapper.class), 180, 90);

        assertThrows(RuntimeException.class, () -> job.roll(YESTERDAY));
    }
}
