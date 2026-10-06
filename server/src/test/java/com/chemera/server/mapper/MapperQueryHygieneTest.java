package com.chemera.server.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G5 的裁判：后台列表那几句 SQL 的<b>形状</b>，不是它们的结果。
 *
 * <p>为什么是静态断言而不是"起个库跑一遍"：这一层要钉的三类问题里，两类（count 与 page 的 WHERE
 * 漂成两份、列表把整份存档搬进 JVM）都是**文本层面的重复与遗漏**——两份手写 SQL 在数据量小的时候
 * 结果永远一致，跑一百遍也是绿的，直到有人按昵称搜一次、面板页数当场是错的。真库覆盖归 e2e（它从
 * HTTP 侧数行数），这里只保证"要改就得同时改，改一份必红"。
 *
 * <p>扫描对象是编译产物里的 {@code @Select} 注解，所以新增一个 mapper 不用登记名单——
 * 它自己就会被扫到。{@code #mappersFoundEnough} 是这条扫描自身的哨兵：万一 classpath 变了导致
 * 一个都没扫到，宁可红，也不要变成一条永远绿的空断言。
 */
class MapperQueryHygieneTest {

    /** 一条 SELECT 注解：稳定 id、去空白并剥掉动态标签后的 SQL、注解原文、返回类型是不是 List。 */
    private record Query(String id, String sql, String raw, boolean returnsList) {}

    /** 列表语义的方法名前缀：这些是"要给面板渲染的一页"，不是"取一条"。 */
    private static final Pattern LIST_METHOD = Pattern.compile("^(page|list|recent)");

    /**
     * 允许出现大 JSON 列名的两种定点写法：让库里数长度（{@code LENGTH}），或让库里只取一个字段
     * （{@code JSON_EXTRACT(col,'$.x')}）。金币/钻石本来就存在存档 payload 里，后台列表要显示它们，
     * 正确做法正是后者——把整列搬回 JVM 才是 G5 说的那处扫。
     *
     * <p>判据是"列名出现次数 &gt; 定点写法出现次数"，不是"擦掉合法写法后还剩没有关键词"：后者能被
     * 擦除顺序骗过去（{@code LENGTH(payload) AS bytes, payload} 会判绿）。
     */
    private static final Pattern SANCTIONED = Pattern.compile(
            "(LENGTH|OCTET_LENGTH|JSON_LENGTH)\\(\\s*[\\w.]*?(payload|data_json)\\s*\\)"
                    + "|JSON_EXTRACT\\(\\s*[\\w.]*?(payload|data_json)\\s*,\\s*'[^']*'\\s*\\)");

    private static final Pattern BIG_COLUMN = Pattern.compile("\\b(payload|data_json)\\b");

    /** {@code DATE(col)=…}：把列套进函数就废掉 V10 的索引，G5 明确修过一处。 */
    private static final Pattern NON_SARGABLE = Pattern.compile("(?<![\\w.])DATE\\(\\s*\\w+\\s*\\)\\s*=");

    @Test
    void mappersFoundEnough() throws Exception {
        // 扫不到东西时其余断言全部空转，所以先保证扫描本身是有效的
        assertTrue(all().size() >= 25, "只扫到 " + all().size() + " 条 @Select，classpath 变了？");
    }

    /**
     * 后台【用户】的总数与列表必须共用同一份 WHERE（G5 第 2 条）。
     *
     * <p>{@code count()} 以前少了 {@code nickname LIKE} 那半句：按昵称搜索时总数是 0、列表里 3 行，
     * 分页组件按总数算页数，于是"有结果但没有页"。断言逐字相等，比断言结果相等更贴近根因。
     */
    @Test
    void userPageAndCountShareOneWhereClause() throws Exception {
        String page = sqlOf("UserMapper.page");
        String count = sqlOf("UserMapper.count");

        String pw = between(page, "WHERE", "ORDER BY");
        String cw = after(count, "WHERE");
        assertEquals(cw, pw, "count 与 page 的过滤条件必须逐字一致——现在它们是两份手写文本就会漂");
        assertFalse(cw.isEmpty(), "没解析出 WHERE，断言会空转");

        // 漂掉的通常就是下面这三段里的某一段，逐个点名才看得出是"少了 nickname"而不是"少了空格"
        assertTrue(pw.contains("#{q}=''"), "空搜索词放行那段不在了");
        assertTrue(pw.contains("username LIKE"), "username 那段不在了");
        assertTrue(pw.contains("nickname LIKE"), "nickname 那段不在了（历史上就是 count 少了它）");
        String shared = UserMapper.LIST_FILTER.replaceAll("\\s+", " ");
        assertTrue(pw.contains(shared) && cw.contains(shared),
                "两处应当都直接引用 UserMapper.LIST_FILTER，而不是各自抄一遍——抄两遍就会漂");
    }

    /**
     * 共用 WHERE 还不够：page 比 count 多 join 了一张表，只有 join 键唯一、且是 LEFT JOIN，
     * 行数才仍然等于总数。这一条把"count 与 page 自洽"的两个前提都钉住，第二个前提的证据在 DDL 里。
     */
    @Test
    void userPageJoinCannotChangeRowCount() throws Exception {
        String page = sqlOf("UserMapper.page");
        assertTrue(page.contains("LEFT JOIN user_save s ON s.user_id=u.id"),
                "page 对 user_save 必须是 LEFT JOIN 且只按 user_id 关联：换成 INNER 会丢掉没存档的游客，丢掉总数");

        String ddl = migration("V1__baseline.sql");
        String userSave = between(ddl.replaceAll("\\s+", " "), "CREATE TABLE user_save (", "ENGINE=");
        assertTrue(userSave.contains("PRIMARY KEY (user_id)"),
                "user_save 的 user_id 必须是主键：一旦一人多行，page 的行数就不再等于 count 的总数");
    }

    /**
     * 列表查询不许把整份存档/内容原文搬进 JVM（G5 第 4 条，{@code SaveMapper.listRevisionMeta} 的注释依赖的正是这条）。
     *
     * <p>后台【历史】抽屉以前是 {@code SELECT * ... LIMIT 50}：打开一次把 50 份完整存档读进服务端、
     * 再序列化给前端，只为表格里那个"几 KB"。字节数交给 {@code LENGTH(payload)} 在库里数。
     *
     * <p>判据是"列名出现次数 &gt; {@code LENGTH(...)} 出现次数"，不是"擦掉合法写法后还剩没有列名"——
     * 后者能被擦除顺序骗过去（{@code LENGTH(payload) AS bytes, payload} 会绿）。名字带 page/list/recent
     * 的才算列表查询；{@code findRevision} 那种"回滚要读原文"的单条取不算，{@code allEnabled} 是内容
     * 下发通道、本来就要带 data，也不算。
     */
    @Test
    void listQueriesNeverCarryRawArchives() throws Exception {
        List<String> hits = new ArrayList<>();
        for (Query q : all()) {
            if (!q.returnsList() || !LIST_METHOD.matcher(q.id().substring(q.id().indexOf('.') + 1)).find()) continue;
            if (dragsRawArchive(q.sql())) hits.add(q.id());
        }
        assertEquals(List.of(), hits,
                "这些列表查询把整份 JSON 列选进了结果，面板一次要搬几十份存档：改成长度/定点取值");
    }

    /** 按天统计不许再写 {@code DATE(col)=当天}：V10 刚给 last_login_at 建了索引，函数包裹会让它白建。 */
    @Test
    void noDateWrappedEqualityPredicate() throws Exception {
        List<String> hits = new ArrayList<>();
        for (Query q : all()) if (NON_SARGABLE.matcher(q.sql()).find()) hits.add(q.id());
        assertEquals(List.of(), hits, "这些查询把列包进了 DATE()，用不上索引；写成 [当天, 次日) 的区间");
    }

    /**
     * DAU 与新增只有一个算法（G1 和 G5 在同一条 SQL 上交点）。
     *
     * <p>看板现算的 {@code dauToday()} 和日报落库的 {@code statRow()} 以前是两套写法，
     * 同一个指标在两个面板上可以对不上数。现在两处拼的都是 {@link AnalyticsMapper#ACTIVE_ON}
     * 与 {@link AnalyticsMapper#CREATED_ON} 这两个常量——断言引用同一份文本，而不是断言数值碰巧相同。
     */
    @Test
    void dauMetricIsOneFragmentNotTwo() throws Exception {
        String stat = sqlOf("AnalyticsMapper.statRow");
        String dau = sqlOf("AnalyticsMapper.dauOn");
        String nu = sqlOf("AnalyticsMapper.newUsersOn");

        String active = AnalyticsMapper.ACTIVE_ON.replaceAll("\\s+", " ");
        String created = AnalyticsMapper.CREATED_ON.replaceAll("\\s+", " ");
        assertTrue(dau.contains(active), "dauOn 没引用 ACTIVE_ON");
        assertTrue(stat.contains(active), "日报的 dau 没引用 ACTIVE_ON：两套口径迟早自相矛盾");
        assertTrue(nu.contains(created), "newUsersOn 没引用 CREATED_ON");
        assertTrue(stat.contains(created), "日报的 nu 没引用 CREATED_ON");
    }

    /**
     * 状态筛选必须让 {@code idx_status} 有用（G5 第 3 条）：拆成"要么不筛、要么等值"，而不是恒真的 OR。
     *
     * <p>这条断言读的是<b>注解原文</b>：{@code all()} 为了比较文本把 {@code <script>/<if>} 剥掉了，
     * 而这里要的恰恰是这两个标签在不在。
     */
    @Test
    void reportStatusFilterIsIndexable() throws Exception {
        String raw = rawSqlOf("ModerationMapper.pageReports");
        assertTrue(raw.contains("<script>") && raw.contains("<if"),
                "pageReports 应当用 <if> 把状态条件做成可选 WHERE，而不是 (#{st}='' OR status=?)");
        assertTrue(raw.contains("WHERE r.status=#{st}"), "状态条件没落在可索引的 r.status= 上");
        assertFalse(raw.contains("#{st}=''"), "恒真分支还在：idx_status 用不上，未筛选时是全表扫");
    }

    /**
     * 分页列表与它的总数查询共用同一个筛选条件（H6-3）。
     *
     * <p>这条是 {@link #userPageAndCountShareOneWhereClause} 的推广版，区别在覆盖面：G5 那处是人肉发现的
     * 一个 bug，改完之后同样的写法在另外五对里都可能再犯一次——而 H6-3 恰恰要求<b>每个</b>分页列表
     * 都回 {@code {rows,total}}，于是"总数从哪来"变成了六个端点共同的义务。这里按方法名自动配对
     * （{@code page}/{@code pageX} ↔ {@code count}/{@code countX}），新加一个分页列表不需要登记，
     * 只要它没有配套的 count，或者 count 的 WHERE 与列表漂了，当场红。
     *
     * <p>比的是文本不是结果：数据几百行时"按昵称搜出 3 行、总数说 0 条"这种不一致，
     * 跑多少遍用例都看不出来，只有逐字比才拦得住。
     */
    @Test
    void pagedListsComeWithACountAndShareItsWhere() throws Exception {
        List<Query> all = all();
        List<String> missing = new ArrayList<>();
        List<String> drifted = new ArrayList<>();
        int withFilter = 0;
        for (Query q : all) {
            String name = q.id().substring(q.id().indexOf('.') + 1);
            if (!name.startsWith("page")) continue;
            String mapper = q.id().substring(0, q.id().indexOf('.'));
            String sibling = mapper + ".count" + name.substring("page".length());
            Query c = find(all, sibling);
            if (c == null) { missing.add(q.id() + " 缺 " + sibling); continue; }
            String w1 = whereOf(q.sql()), w2 = whereOf(c.sql());
            if (!w1.equals(w2)) drifted.add(q.id() + " 的筛选条件是「" + w1 + "」，" + sibling + " 是「" + w2 + "」");
            if (!w1.isEmpty()) withFilter++;
        }
        assertEquals(List.of(), missing, "这些分页列表没有配套的总数查询，面板只好拿本页长度编一个总数");
        assertEquals(List.of(), drifted, "总数与列表各抄一份 WHERE 就会漂：把条件抽成一个常量，两处引用它");
        // 抽取本身不能是空转的：所有配对都"没有 WHERE"时，上面那条比较永远绿
        assertTrue(withFilter >= 3, "只从 " + withFilter + " 对分页查询里抽出过筛选条件，whereOf 大概失效了");
    }

    /**
     * 分页列表的排序必须收在一个唯一列上（H6-3 顺带踩到的坑）。
     *
     * <p>{@code ORDER BY sort} 这种写法在"同类型一堆行都是 0"的表上是不确定的顺序：
     * {@code LIMIT/OFFSET} 只保证取"这个顺序下"的第 N 到 M 行，而顺序本身不唯一时，同一行可以在
     * 第 1 页和第 2 页各出现一次、另一行两页都不出现——面板逐页翻下去看到重复行，且和"共 N 条"对不上。
     * 所以要求最后一个排序键落在表的主键 / 唯一键上（复合唯一键里取其一也算，前提是另一列已被 WHERE 钉住，
     * {@code content_item} 正是这种：{@code WHERE content_type=#{type}} 之后 {@code item_id} 就唯一了）。
     */
    @Test
    void pagedListsOrderEndInAUniqueColumn() throws Exception {
        String ddl = migration("V1__baseline.sql");
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (Query q : all()) {
            String name = q.id().substring(q.id().indexOf('.') + 1);
            if (!name.startsWith("page") || !q.returnsList()) continue;
            checked++;
            String afterFrom = after(q.sql(), " FROM ");
            String table = afterFrom.split("[\\s,()]+")[0];
            String last = orderByTail(q.sql());
            if (last == null) { bad.add(q.id() + " 没有 ORDER BY：OFFSET 的分页在无排序时结果未定义"); continue; }
            if (!uniqueColumns(ddl, table).contains(last))
                bad.add(q.id() + " 按「" + last + "」收尾，而 " + table + " 上没有任何唯一键包含它：同值行的页间顺序不保证");
        }
        assertEquals(List.of(), bad, "分页要可复现，排序最后一名得是唯一列");
        assertTrue(checked >= 6, "只查了 " + checked + " 个分页查询，name.startsWith(\"page\") 这条筛法大概失效了");
    }

    /**
     * {@code countReports} 不带那两个 {@code LEFT JOIN} 是<b>有前提</b>的优化，不是随手写的：
     * 只有 join 键是被join表的主键（一人至多一行）时，去掉 join 才不改行数。这条把前提钉住——
     * 哪天 {@code report} 多一个 join 或 {@code app_user.id} 不再是主键，这里红，而不是总数静默变成两倍。
     */
    @Test
    void reportCountNeedsNoJoins() throws Exception {
        String count = sqlOf("ModerationMapper.countReports");
        assertFalse(count.toUpperCase().contains("JOIN"),
                "总数查询一旦带上 join，行数就可能不是表本身的行数：这里靠 page 那两个按主键的 LEFT JOIN 不放大行数来省掉它们");
        String page = sqlOf("ModerationMapper.pageReports");
        assertTrue(page.contains("LEFT JOIN app_user ru ON ru.id=r.reporter")
                        && page.contains("LEFT JOIN app_user tu ON tu.id=r.target_user"),
                "page 那两个 join 必须仍是 LEFT JOIN 且按 app_user.id 关联，count 省略它们才成立");
        String ddl = migration("V1__baseline.sql").replaceAll("\\s+", " ");
        String appUser = between(ddl, "CREATE TABLE app_user (", "ENGINE=");
        assertTrue(appUser.contains("PRIMARY KEY (id)"), "app_user.id 不再是主键：一个用户多行会让 page 的行数超过 count 的总数");
    }

    /**
     * 这些静态裁判的反证：把"当年那句"喂给自己的判据，它必须报红。
     *
     * <p>静态断言最容易犯的错是写成一个永远绿的形状（正则不匹配、扫描范围为空、擦除顺序刚好把违规擦干净）。
     * 数值恰好相同不能证明判据有效，这几行是它唯一能被证明的地方。
     */
    @Test
    void theRulersThemselvesBite() throws Exception {
        // 列表 payload 判据
        assertFalse(dragsRawArchive("SELECT revision,LENGTH(payload) bytes FROM user_save_revision"),
                "定点数长度被误判成违规——那这条规则下次会被整条删掉");
        assertFalse(dragsRawArchive("SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(s.payload,'$.coins')) AS SIGNED) coins FROM t"),
                "定点取一个字段是允许的：金币钻石本来就存在存档里，列表要显示它们");
        assertTrue(dragsRawArchive("SELECT JSON_EXTRACT(payload,'$.coins'),payload FROM t"),
                "定点取值之外还把原文也选出来，必须判红");
        assertTrue(dragsRawArchive("SELECT LENGTH(payload) bytes,payload FROM user_save_revision"),
                "「合规前缀 + 违规尾巴」必须判红：擦除式判据正是在这里会误绿");
        assertFalse(dragsRawArchive("SELECT payload_size FROM t"), "列名要有词边界，payload_size 不算 payload");
        // 非 sargable 判据
        assertTrue(NON_SARGABLE.matcher("SELECT COUNT(*) FROM app_user WHERE DATE(last_login_at)=CURDATE()").find(),
                "G5 修掉的那个写法得能被抓到");
        assertFalse(NON_SARGABLE.matcher("WHERE last_login_at >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY DATE(last_login_at)").find(),
                "GROUP BY 里的函数包裹不影响走索引，别把它一起判死");
        // 分页配对判据（H6-3）：这条最怕的是"两处都抽不出 WHERE"，那样两个空串永远相等
        assertFalse(whereOf("SELECT * FROM t WHERE a=1 ORDER BY id LIMIT 10").isEmpty(),
                "WHERE 段切不出来时，比较的双方都是空串、这条判据就再也不会红");
        assertEquals(whereOf("SELECT * FROM t WHERE a=1 ORDER BY id"), whereOf("SELECT COUNT(*) FROM t WHERE a=1"),
                "同一条件写在两条 SQL 里应当判等（尾巴上没有 ORDER BY 也得切干净）");
        assertNotEquals(whereOf("SELECT * FROM t WHERE a=1"), whereOf("SELECT COUNT(*) FROM t"),
                "count 少了筛选条件正是 G5 那个 bug 的形状，推广版必须抓得住它");
        assertEquals("", whereOf("SELECT * FROM t ORDER BY id"), "没有 WHERE 时回空串（整表分页那两两相等才成立）");
        // 排序末名判据
        assertEquals("id", orderByTail("SELECT * FROM t ORDER BY sort, u.id DESC LIMIT 5"), "排序末名要落到列名本身，别把别名和方向词留在串里");
        assertNull(orderByTail("SELECT * FROM t LIMIT 5"), "没有 ORDER BY 时得报 null，好让上面那条把它判红而不是当成空列名放行");
        String ddl = migration("V1__baseline.sql");
        assertTrue(uniqueColumns(ddl, "content_item").contains("item_id"),
                "复合主键里的列也要算唯一列，否则【内容】列表那个合法的 tiebreak 会被误判");
        assertFalse(uniqueColumns(ddl, "content_item").contains("sort"),
                "sort 不唯一：按它收尾的分页正是这次踩到的坑，判据不能把它当唯一列放过");
    }

    // ---------- 扫描与取值 ----------

    private static List<Query> all() throws Exception {
        Resource[] rs = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/chemera/server/mapper/*.class");
        List<Query> out = new ArrayList<>();
        for (Resource r : rs) {
            String f = r.getFilename();
            if (f == null || !f.endsWith(".class") || f.contains("$")) continue;
            Class<?> t = Class.forName("com.chemera.server.mapper." + f.replace(".class", ""),
                    false, MapperQueryHygieneTest.class.getClassLoader());
            if (!t.isInterface()) continue;
            for (Method m : t.getDeclaredMethods()) {
                Select s = m.getAnnotation(Select.class);
                if (s == null) continue;
                String raw = String.join(" ", s.value()).replaceAll("\\s+", " ").trim();
                out.add(new Query(t.getSimpleName() + "." + m.getName(),
                        // 只剥 MyBatis 的动态 SQL 标签。曾经这里是通吃的 <[^>]+>，而 ACTIVE_ON 里有
                        // "last_login_at < DATE_ADD(...)"——小于号被当成标签开头，一句正常的 SQL 被撕成两半，
                        // 于是 dauMetricIsOneFragmentNotTwo 红了。裁判自己也得是干净的。
                        TAGS.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim(),
                        raw, List.class.isAssignableFrom(m.getReturnType())));
            }
        }
        return out;
    }

    private static String sqlOf(String id) throws Exception {
        for (Query q : all()) if (q.id().equals(id)) return q.sql();
        fail("没找到 " + id + " 的 @Select：改名或删掉这条断言，别让它静默通过");
        return null;
    }

    /** 取 {@code from} 之后到 {@code to} 之前的片段（{@code to} 缺失就取到结尾），用于切出 WHERE 段。 */
    private static String between(String s, String from, String to) {
        int a = s.indexOf(from);
        assertTrue(a >= 0, "没找到 " + from + "：" + s);
        int b = s.indexOf(to, a);
        return (b < 0 ? s.substring(a + from.length()) : s.substring(a + from.length(), b)).trim();
    }

    private static String after(String s, String from) {
        int a = s.indexOf(from);
        assertTrue(a >= 0, "没找到 " + from + "：" + s);
        return s.substring(a + from.length()).trim();
    }

    private static String migration(String name) throws Exception {
        Resource r = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/" + name);
        assertTrue(r.exists(), "找不到 " + name + "：迁移文件改名了，DDL 断言要跟着改");
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 列表查询是否把整份大 JSON 列原文带进结果：出现次数多于定点写法次数就是带了。 */
    private static boolean dragsRawArchive(String sql) {
        return count(BIG_COLUMN, sql) > count(SANCTIONED, sql);
    }

    private static Query find(List<Query> all, String id) {
        for (Query q : all) if (q.id().equals(id)) return q;
        return null;
    }

    /**
     * 一条 SQL 的筛选段：{@code WHERE} 到 {@code ORDER BY}/{@code GROUP BY}/{@code LIMIT} 之前；
     * 没有 WHERE 就是空串（{@code pageWords}/{@code countWords} 那种"整表分页"正是两个空串相等）。
     */
    private static String whereOf(String sql) {
        int a = sql.indexOf(" WHERE ");
        if (a < 0) return "";
        return cut(sql.substring(a + " WHERE ".length()));
    }

    /** 排序段最后一名用的列名（去掉表限定符与 ASC/DESC）；没有 ORDER BY 返回 null。 */
    private static String orderByTail(String sql) {
        int a = sql.indexOf(" ORDER BY ");
        if (a < 0) return null;
        String seg = cut(sql.substring(a + " ORDER BY ".length()));
        String[] parts = seg.split(",");
        String last = parts[parts.length - 1].trim();
        last = last.replaceAll("(?i)\\s+(asc|desc)$", "").trim();
        int dot = last.lastIndexOf('.');
        return (dot < 0 ? last : last.substring(dot + 1)).replace("`", "").toLowerCase();
    }

    /** {@code ORDER BY}/{@code GROUP BY}/{@code LIMIT} 谁先来就切在哪，剩下的当尾巴。 */
    private static String cut(String s) {
        int stop = s.length();
        for (String kw : new String[]{" ORDER BY ", " GROUP BY ", " LIMIT ", " HAVING "}) {
            int i = s.indexOf(kw);
            if (i >= 0 && i < stop) stop = i;
        }
        return s.substring(0, stop).trim();
    }

    /**
     * 一张表在基线 DDL 里被唯一性约束覆盖的列（主键与唯一键的每一列都算）。
     *
     * <p>复合唯一键在这里摊平成"每一列都算"，是刻意放宽：{@code content_item} 的主键是
     * {@code (content_type,item_id)}，而它的分页查询在 WHERE 里钉住了 {@code content_type}，
     * 所以按 {@code item_id} 收尾确实唯一。静态分析要证到这一层就得解析 SQL，代价不值；
     * 真正的哨兵是 {@code e2e-api.sh} 那段逐页走查——它拿真数据翻一遍，重复行当场可见。
     */
    private static java.util.Set<String> uniqueColumns(String ddl, String table) {
        String body = between(ddl.replaceAll("\\s+", " "), "CREATE TABLE " + table + " (", "ENGINE=");
        java.util.Set<String> out = new java.util.HashSet<>();
        java.util.regex.Matcher m = UNIQUE_KEY.matcher(body);
        while (m.find())
            for (String col : m.group(1).split(","))
                out.add(col.trim().replace("`", "").toLowerCase());
        return out;
    }

    private static final Pattern UNIQUE_KEY = Pattern.compile("(?:PRIMARY|UNIQUE) KEY [\\w`]*\\s*\\(([^)]*)\\)");

    private static int count(Pattern p, String s) {
        int n = 0;
        Matcher m = p.matcher(s);
        while (m.find()) n++;
        return n;
    }

    /** 比较符会被通吃的 {@code <[^>]+>} 当成标签吃掉，所以只剥 MyBatis 那几个动态 SQL 标签。 */
    private static final Pattern TAGS = Pattern.compile(
            "</?(?:script|if|where|set|foreach|trim|choose|when|otherwise|bind)\\b[^>]*>");

    /** 注解原文（只压空白、不剥标签）：{@code #reportStatusFilterIsIndexable} 断的正是标签在不在。 */
    private static String rawSqlOf(String id) throws Exception {
        for (Query q : all()) if (q.id().equals(id)) return q.raw();
        fail("没找到方法 " + id + " 的 @Select");
        return "";
    }
}
