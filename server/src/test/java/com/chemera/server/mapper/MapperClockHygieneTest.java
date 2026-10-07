package com.chemera.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时钟的裁判：一条 SQL 里的"现在"到底是谁的现在。
 *
 * <p>起因是迭代 4 的 CI 首跑（本地全绿、GitHub 上 51 条红）。工作流给 job 设了
 * {@code TZ: Asia/Shanghai}，后端 JVM 因此在 +08:00；而那只 MySQL 服务容器没设时区，跑的是 UTC。
 * {@code user_session} 的 {@code rotated_at}/{@code revoked_at} 当时由 SQL 的 {@code NOW()} 盖（库钟，UTC），
 * {@code AuthService.refresh} 却拿 {@code LocalDateTime.now()}（进程钟，+08:00）去判"是否超出 15 秒轮换宽限期"
 * ——差 8 小时，于是刚轮换完那一行立刻算超期，整户 {@code revokeAll}，玩家被踢下线，
 * 后面每一条带玩家令牌的断言级联成 401。这不是测试写错了，是<b>生产也会有的形状</b>：
 * 只要库和进程不在同一个时区（Docker 默认就是不同时区），玩家就会莫名其妙反复掉线。
 *
 * <p>守的规矩只有一句：<b>谁判断，谁的时钟写</b>。一列的时间戳必须由"将来要拿它做判断的那一方"来盖——
 * 会话生命周期、按天统计、广告工单的签发与窗口全部由 Java 判断，所以它们现在收 {@code #{now}}/{@code #{day}}/
 * {@code #{since}}；只剩两处真正的"库钟孤本"（见 {@link #SANCTIONED}），因为它们写入的时刻没有任何人拿去判断。
 *
 * <p>为什么是静态断言而不是"起个库跑一遍"：这一层要钉的是<b>文本上的两套钟</b>。数据量小、时区碰巧一致时，
 * 结果永远正确，跑一百遍也是绿的——CI 那次的时区差恰好证明了"跑一遍真库"并不能替代这条。
 * 扫描对象是编译产物里的注解，所以新增一个 mapper 不用登记名单，它自己就会被扫到。
 */
class MapperClockHygieneTest {

    /** 一条带 SQL 的注解：稳定 id、种类、压掉空白的 SQL 原文。 */
    private record Ann(String id, String kind, String sql) {}

    /**
     * 库侧"现在"的四种写法。 {@code DATE_ADD}/{@code DATE_SUB} 不在内：那是对<b>传进来的</b>日期做加减，
     * 时钟仍然来自调用方（{@code ACTIVE_ON} 里的 {@code DATE_ADD(#{d}, INTERVAL 1 DAY)} 正是这种合法用法）。
     */
    private static final Pattern DB_CLOCK = Pattern.compile(
            "\\b(NOW\\s*\\(|CURRENT_TIMESTAMP\\b|CURDATE\\s*\\(|CURRENT_DATE\\b)", Pattern.CASE_INSENSITIVE);

    /**
     * 允许继续用库钟的两处，以及各自为什么不算破规矩。
     *
     * <p>这张表是<b>显式的债</b>：想新增一条必须在这里写下理由，写不出理由就说明那一列将来会被判断、
     * 应该改成传参。空集也当作有效状态对待（{@link #dbClockSurvivesOnlyWhereNothingJudgesIt} 逐条比对 id）。
     */
    private static final Map<String, String> SANCTIONED = Map.of(
            "AdTicketMapper.markRewarded",
            "rewarded_at 是「回调验签通过那一刻」的取证戳，没有任何代码拿它做判断；与 content/config 上那两处 "
                    + "ON UPDATE CURRENT_TIMESTAMP(3) 同类——乐观锁的戳刻意留在库侧，免得两笔写的先后由某个 JVM 的钟说了算",
            "ModerationMapper.countToday",
            "created_at 由库钟盖（V1 的 DEFAULT CURRENT_TIMESTAMP），而这条窗口也用库钟，同一列同一支钟；"
                    + "它不是玩家可预期的「今日次数」（那些走意图钟，见 GameService.rollAdDay），是服务端限流闸门。"
                    + "部署契约仍然要求库的时区等于应用声明的时区（application-mysql.yml 的 serverTimezone + CI 里那只容器的 TZ）");

    @Test
    void annotationsScannedAreEnoughToBeARealRuler() throws Exception {
        // 扫描本身失效时，下面几条会集体空转变绿；先保证抓到了足够多条 SQL
        assertTrue(all().size() >= 60, "只扫到 " + all().size() + " 条带 SQL 的注解，classpath 变了？");
    }

    /** 主断言：库钟只剩名单里那两处，多一处（少一处）都要显式承认。 */
    @Test
    void dbClockSurvivesOnlyWhereNothingJudgesIt() throws Exception {
        List<String> hits = new ArrayList<>();
        for (Ann a : all()) if (DB_CLOCK.matcher(a.sql()).find()) hits.add(a.id());
        assertEquals(new TreeSet<>(SANCTIONED.keySet()), new TreeSet<>(hits),
                "这些 SQL 用了库钟，而它判断的那一列很可能由进程钟写（或反过来）——"
                        + "把时间改成参数传进来，或者说明这一列没人判断，再登记进 SANCTIONED");
        assertTrue(SANCTIONED.size() >= 2, "名单本身不该被清空：清空后这条断言就变成\"任何地方都不许用库钟\"，会被整条删掉");
    }

    /**
     * 反向哨兵：{@code user_session} 这一张表的写点必须都带着传进来的钟。
     *
     * <p>CI 那次红的三条 UPDATE（{@code revoke}/{@code rotate}/{@code revokeAll}）加两条读
     * （{@code liveCount}/{@code countActive}）就是这个形状。它们各自漏掉 {@code #{now}} 都会当场红，
     * 而不是等到某台机器时区不一样时才在玩家身上红。
     */
    @Test
    void sessionLifecycleCarriesTheAppClock() throws Exception {
        for (String id : List.of("SessionMapper.liveCount", "SessionMapper.revoke",
                "SessionMapper.rotate", "SessionMapper.revokeAll", "SessionMapper.countActive")) {
            String sql = sqlOf(id);
            assertTrue(sql.contains("#{now}"), id + " 没把时间当参数收：谁判断就该谁的时钟写");
            assertFalse(DB_CLOCK.matcher(sql).find(), id + " 里还混着库钟：" + sql);
        }
        // insert 那一行是唯一写 expires_at 的地方，而 expires_at 正是上面两条读判断比对的对象
        assertTrue(sqlOf("SessionMapper.insert").contains("#{expiresAt}"),
                "会话行的到期时间必须由实体带进来，否则 liveCount 拿进程钟判一列库钟写的数");
    }

    /**
     * 其余"由 Java 判断"的列同一条规矩，逐个点名：
     * 按天统计比的是进程算出来的日子，广告窗口比的是进程算出来的窗口起点。
     */
    @Test
    void dayBucketsAndAdWindowsTakeTheirBoundFromJava() throws Exception {
        assertTrue(sqlOf("UserMapper.touchLogin").contains("last_login_at=#{now}"),
                "last_login_at 是 DAU/登录趋势分桶的依据，必须由进程钟盖");
        assertTrue(sqlOf("AnalyticsMapper.insert").contains("#{day}"),
                "analytics_event.day 曾被 CURRENT_DATE() 写、被 LocalDate.now() 读：跨钟");
        for (String id : List.of("AnalyticsMapper.loginTrend", "AnalyticsMapper.eventCounts",
                "AnalyticsMapper.purgeOld")) {
            assertTrue(sqlOf(id).contains("#{since}") || sqlOf(id).contains("#{before}"),
                    id + " 的窗口起点应当由调用方算好传进来");
        }
        String issue = sqlOf("AdTicketMapper.insert");
        assertTrue(issue.contains("issued_at") && issue.contains("#{issuedAt}"),
                "工单的签发时刻与它的窗口/清理用的是同一支钟，就得由实体带进来");
        for (String id : List.of("AdTicketMapper.statusCounts", "AdTicketMapper.kindCounts",
                "AdTicketMapper.purgeOld")) {
            assertTrue(sqlOf(id).contains("#{since}") || sqlOf(id).contains("#{before}"),
                    id + " 还在拿 CURDATE()/NOW() 切窗口");
        }
    }

    /**
     * 这条裁判的反证：把当年那句喂给自己的判据，它必须报红。
     *
     * <p>静态断言最容易写成一个永远绿的形状（正则不匹配、扫描范围为空、名单被顺手清空）。
     * 这几行是它唯一能被证明的地方——尤其"合法用法不许误判"那半：
     * {@code DATE_ADD(#{d}, INTERVAL 1 DAY)} 要是被判红，下一次就有人把整条规则删掉。
     */
    @Test
    void theRulerItselfBites() throws Exception {
        assertTrue(DB_CLOCK.matcher("UPDATE user_session SET rotated_at=NOW() WHERE refresh_hash=#{hash}").find(),
                "CI 那次红的确切写法得能被抓到");
        assertTrue(DB_CLOCK.matcher("SELECT COUNT(*) FROM report WHERE created_at >= CURDATE()").find());
        assertTrue(DB_CLOCK.matcher("INSERT INTO analytics_event(day) VALUES(CURRENT_DATE())").find());
        assertTrue(DB_CLOCK.matcher("UPDATE t SET x=1 WHERE d < DATE_SUB(NOW(), INTERVAL 7 DAY)").find());
        assertFalse(DB_CLOCK.matcher("SELECT COUNT(*) FROM app_user WHERE last_login_at >= #{d}"
                        + " AND last_login_at < DATE_ADD(#{d}, INTERVAL 1 DAY)").find(),
                "DATE_ADD 只是对传进来的日期做加减，不是库钟：把它一起判死，这条规则迟早被人整条删掉");
        assertFalse(DB_CLOCK.matcher("UPDATE app_user SET last_login_at=#{now} WHERE id=#{id}").find());
        // 名单必须真能被"新增一处"撑破：拿一条现在绿着的 SQL 换成库钟写法，判据要报红
        assertFalse(DB_CLOCK.matcher(sqlOf("AnalyticsMapper.dauOn")).find(), "dauOn 现在应当是干净的");
        assertTrue(DB_CLOCK.matcher(sqlOf("AnalyticsMapper.dauOn").replace("#{d}", "CURDATE()")).find(),
                "把 #{d} 换成 CURDATE() 后判据抓不到，说明它其实什么都没看");
    }

    /** 名单里登记的每一处，今天必须确实还在用库钟：改了写法就要跟着改名单，不许留陈年借口。 */
    @Test
    void sanctionedEntriesAreStillTrue() throws Exception {
        for (String id : SANCTIONED.keySet()) {
            String sql = sqlOf(id);
            assertTrue(DB_CLOCK.matcher(sql).find(), id + " 已经不用库钟了，把它从 SANCTIONED 里删掉");
        }
    }

    // ---------- 扫描与取值 ----------

    private static List<Ann> all() throws Exception {
        Resource[] rs = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/chemera/server/mapper/*.class");
        List<Ann> out = new ArrayList<>();
        for (Resource r : rs) {
            String f = r.getFilename();
            if (f == null || !f.endsWith(".class") || f.contains("$")) continue;
            Class<?> t = Class.forName("com.chemera.server.mapper." + f.replace(".class", ""),
                    false, MapperClockHygieneTest.class.getClassLoader());
            if (!t.isInterface()) continue;
            for (Method m : t.getDeclaredMethods()) {
                for (Class<? extends Annotation> kind : List.of(Select.class, Insert.class, Update.class, Delete.class)) {
                    Annotation a = m.getAnnotation(kind);
                    if (a == null) continue;
                    String sql = value(a);
                    if (sql == null) continue;
                    out.add(new Ann(t.getSimpleName() + "." + m.getName(),
                            kind.getSimpleName(), sql.replaceAll("\\s+", " ").trim()));
                }
            }
        }
        return out;
    }

    /** 四种注解都是 `String[] value()`，但没有公共接口可依赖，只能各取一次。 */
    private static String value(Annotation a) {
        if (a instanceof Select s) return String.join(" ", s.value());
        if (a instanceof Insert i) return String.join(" ", i.value());
        if (a instanceof Update u) return String.join(" ", u.value());
        if (a instanceof Delete d) return String.join(" ", d.value());
        return null;
    }

    private static Map<String, String> cache() throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        for (Ann a : all()) m.put(a.id(), a.sql());
        return m;
    }

    private static String sqlOf(String id) throws Exception {
        String sql = cache().get(id);
        assertNotNull(sql, "没找到 " + id + " 的 SQL 注解：改名或删掉这条断言，别让它静默通过");
        return sql;
    }
}
