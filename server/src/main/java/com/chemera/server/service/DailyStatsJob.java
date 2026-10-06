package com.chemera.server.service;

import com.chemera.server.mapper.AdTicketMapper;
import com.chemera.server.mapper.AnalyticsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日报聚合与老数据清理（G1）。
 *
 * <p>这块面板原来是断的：{@code daily_stats} 表从 V1 就建好了，看板 {@code trend} 也在读它
 * （{@code AnalyticsMapper.recentDaily}），但<b>没有任何人往里写</b>——{@code upsertDaily} 零调用者，
 * 那张表恒为 0 行。恒 0 的面板比没有这块面板更糟：运营会读成"今天没人玩"。
 * 同一个道理也适用于两张只增不减的表：{@code analytics_event.purgeOld} 与
 * {@code ad_ticket.purgeOld} 都写好了却没人调，埋点表就是一张永远长大的表。
 *
 * <p><b>为什么算昨天而不是今天</b>：{@code dau}/{@code new_users} 取的是 {@code app_user} 上的
 * {@code last_login_at} 与 {@code created_at}，今天这两个值都还在动，此刻写进去的那一行
 * 到了明天就是错的（而且再也不会被重算）。所以固定每天凌晨把<b>昨天</b>结清，另外顺带把
 * 前天补一次——跨零点那几分钟的登录/事件会算到前一天去，补一次就把它们归位。
 *
 * <p>没有引调度框架：单机部署（见 G7），形状照 {@link SessionJanitor}，一个 {@code @Scheduled} 足够。
 */
@Component
public class DailyStatsJob {
    private static final Logger log = LoggerFactory.getLogger(DailyStatsJob.class);

    private final AnalyticsMapper analytics;
    private final AdTicketMapper tickets;
    private final int eventKeepDays;
    private final int ticketKeepDays;

    public DailyStatsJob(AnalyticsMapper analytics, AdTicketMapper tickets,
                         @Value("${chemera.stats.event-keep-days:180}") int eventKeepDays,
                         @Value("${chemera.stats.ticket-keep-days:90}") int ticketKeepDays) {
        this.analytics = analytics; this.tickets = tickets;
        this.eventKeepDays = Math.max(7, eventKeepDays);
        this.ticketKeepDays = Math.max(7, ticketKeepDays);
    }

    /** 启动后一小时先跑一次（免得部署时间正好错过凌晨点），之后每天一次。 */
    @Scheduled(initialDelay = 3600_000L, fixedDelay = 24 * 3600_000L)
    public void sweep() {
        LocalDate today = LocalDate.now();
        roll(today.minusDays(1));
        roll(today.minusDays(2));
        int ev = analytics.purgeOld(eventKeepDays);
        int tk = tickets.purgeOld(ticketKeepDays);
        if (ev > 0 || tk > 0) log.info("老数据清理：analytics_event {} 行（保留 {} 天），ad_ticket {} 行（保留 {} 天）",
                ev, eventKeepDays, tk, ticketKeepDays);
    }

    /**
     * 把某一天的四项汇总写进 {@code daily_stats}，返回写入的那一行（供后台【重算】按钮回显）。
     * 单条 SQL 聚合 + upsert，所以重复调用只会覆盖成同一个值——补数据不用先删。
     */
    public Map<String, Object> roll(LocalDate day) {
        Map<String, Object> row = analytics.statRow(day);
        int dau = num(row, "dau"), nu = num(row, "nu"), rx = num(row, "rx"), bm = num(row, "bm"), tr = num(row, "tr");
        analytics.upsertDaily(day, dau, nu, rx, bm, tr);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.toString());
        out.put("dau", dau); out.put("newUsers", nu);
        out.put("reactions", rx); out.put("booms", bm); out.put("trades", tr);
        log.info("日报落库 day={} dau={} new={} rx={} boom={} trade={}", day, dau, nu, rx, bm, tr);
        return out;
    }

    private static int num(Map<String, Object> row, String k) {
        Object v = row == null ? null : row.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }
}
