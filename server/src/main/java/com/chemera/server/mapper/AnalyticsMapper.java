package com.chemera.server.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {
    /**
     * 埋点落库：{@code day} 由调用方给（{@link #insert(Long, String, String)} 用 JVM 的当天），
     * 不再让 {@code CURRENT_DATE()} 来定。
     *
     * <p>因为这张表的 {@code day} 后来是被 Java 的日期读回来的：{@link #statRow} 拿
     * {@code day=#{d}}（{@code d} 来自 {@code LocalDate.now()}）、日报 {@code upsertDaily} 也写
     * {@code LocalDate}。写入用库钟、读取用进程钟，两边一旦不在同一个时区，
     * "今天"就有 8 小时对不上——看板会显示昨天没人事件，而昨天那一行明明已经落过。
     * 规矩：<b>谁判断，谁的时钟写</b>。
     */
    @Insert("INSERT INTO analytics_event(user_id,event,props,day) VALUES(#{userId},#{event},#{props},#{day})")
    int insert(@Param("userId") Long userId, @Param("event") String event, @Param("props") String props,
               @Param("day") java.time.LocalDate day);

    /** 常规埋点入口：按应用侧的当天归档。 */
    default int insert(Long userId, String event, String props) {
        return insert(userId, event, props, java.time.LocalDate.now());
    }

    /** 游客转正：埋点事件换主。 */
    @Update("UPDATE analytics_event SET user_id=#{to} WHERE user_id=#{from}")
    int reassignUser(@Param("from") long from, @Param("to") long to);

    /**
     * 登录趋势：{@code since} 是"往回看到哪天"的那个日期，由调用方算（{@link #loginTrend(int)}）。
     * 以前这里是 {@code DATE_SUB(CURDATE(), INTERVAL #{days} DAY)}——判断"最近 7 天"用的是库钟，
     * 而被判断的 {@code last_login_at} 是进程钟写的（{@code UserMapper.touchLogin}），跨钟。
     */
    @Select("SELECT DATE(last_login_at) d, COUNT(DISTINCT id) n FROM app_user " +
            "WHERE last_login_at >= #{since} GROUP BY d ORDER BY d")
    List<Map<String, Object>> loginTrend(@Param("since") java.time.LocalDate since);

    default List<Map<String, Object>> loginTrend(int days) {
        return loginTrend(java.time.LocalDate.now().minusDays(days));
    }

    @Select("SELECT event, COUNT(*) n FROM analytics_event WHERE day >= #{since} " +
            "GROUP BY event ORDER BY n DESC")
    List<Map<String, Object>> eventCounts(@Param("since") java.time.LocalDate since);

    default List<Map<String, Object>> eventCounts(int days) {
        return eventCounts(java.time.LocalDate.now().minusDays(days));
    }

    /**
     * "某一天有过登录"与"某一天建的号"两段谓词（G5）。
     *
     * <p>写成区间而不是 {@code DATE(col)=当天}：把列套进函数里，V10 刚建的
     * {@code idx_last_login}/{@code idx_created} 就用不上了（非 sargable），每天一次的看板查询
     * 变成全表扫。区间形式对同一天的定义与原来严格等价——{@code [d, d+1天)}。
     *
     * <p>它们之所以是接口常量而不是各自抄一遍：现算的 {@code dauOn} 与日报的 {@code statRow}
     * 算的是同一个指标。同一个指标有两套 SQL，迟早有一天两套各自漂移（G1 那条纪律）。
     */
    String ACTIVE_ON = "last_login_at >= #{d} AND last_login_at < DATE_ADD(#{d}, INTERVAL 1 DAY)";
    String CREATED_ON = "created_at >= #{d} AND created_at < DATE_ADD(#{d}, INTERVAL 1 DAY)";

    @Select("SELECT COUNT(*) FROM app_user WHERE " + ACTIVE_ON)
    long dauOn(@Param("d") java.time.LocalDate d);

    @Select("SELECT COUNT(*) FROM app_user WHERE " + CREATED_ON)
    long newUsersOn(@Param("d") java.time.LocalDate d);

    /**
     * 今日活跃 / 今日新增：都转发到按天的那两条。
     * 以前的写法是 {@code DATE(last_login_at)=CURDATE()}，数值一样但用不上索引；
     * 而"今天"和"某一天"如果各留一份 SQL，日报与看板就会出现同一个数对不上的那天。
     */
    default long dauToday() { return dauOn(java.time.LocalDate.now()); }

    default long newUsersToday() { return newUsersOn(java.time.LocalDate.now()); }

    @Select("SELECT COUNT(*) FROM app_user")
    long totalUsers();

    @Insert("INSERT INTO daily_stats(day,dau,new_users,reactions,booms,trades) VALUES(#{day},#{dau},#{nu},#{rx},#{bm},#{tr}) " +
            "ON DUPLICATE KEY UPDATE dau=VALUES(dau),new_users=VALUES(new_users),reactions=VALUES(reactions),booms=VALUES(booms),trades=VALUES(trades)")
    int upsertDaily(@Param("day") java.time.LocalDate day, @Param("dau") int dau, @Param("nu") int nu,
                    @Param("rx") int rx, @Param("bm") int bm, @Param("tr") int tr);

    /**
     * 某一天的四项汇总，一条 SQL 出齐（G1）。
     *
     * <p>DAU 与新增取 {@code app_user}，反应/事故/成交取 {@code analytics_event}，两边口径与
     * {@code overview} 现算的那几个数一致——同一个指标有两套算法，看板迟早自相矛盾。
     * 别名不带下划线是刻意的：返回 {@code Map} 时 MyBatis 的驼峰映射只作用于实体，不作用于 Map 的 key。
     */
    @Select("SELECT " +
            "(SELECT COUNT(*) FROM app_user WHERE " + ACTIVE_ON + ") dau," +
            "(SELECT COUNT(*) FROM app_user WHERE " + CREATED_ON + ") nu," +
            "(SELECT COUNT(*) FROM analytics_event WHERE day=#{d} AND event='react_success') rx," +
            "(SELECT COUNT(*) FROM analytics_event WHERE day=#{d} AND event='react_boom') bm," +
            "(SELECT COUNT(*) FROM analytics_event WHERE day=#{d} AND event IN ('sell','buy','trade')) tr")
    Map<String, Object> statRow(@Param("d") java.time.LocalDate d);

    @Select("SELECT day,dau,new_users,reactions,booms,trades FROM daily_stats ORDER BY day DESC LIMIT #{limit}")
    List<Map<String, Object>> recentDaily(int limit);

    /**
     * 老事件清掉：这张表原来只增不减（{@code purgeOld} 零调用者），日报上线后由 {@code DailyStatsJob} 调。
     *
     * <p>{@code before} 由调用方算：删的是"应用侧视角的 N 天前"，判断者与写入 {@code day} 的那一方是同一个钟。
     */
    @Delete("DELETE FROM analytics_event WHERE day < #{before}")
    int purgeOld(@Param("before") java.time.LocalDate before);

    default int purgeOld(int keepDays) {
        return purgeOld(java.time.LocalDate.now().minusDays(keepDays));
    }

    /** 注销账号：清除该用户埋点。 */
    @Delete("DELETE FROM analytics_event WHERE user_id=#{id}")
    int purgeUser(long id);
}
