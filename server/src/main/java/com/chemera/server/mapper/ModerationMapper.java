package com.chemera.server.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface ModerationMapper {
    /**
     * 举报列表的筛选条件（H6-3）：总数与列表引用<b>同一段文本</b>，因为分页组件的页数吃的是总数，
     * 而它旁边的表格吃的是这一页——两处 WHERE 漂开，表现是"搜出来 3 行、页数说共有 0 条"。
     * {@code MapperQueryHygieneTest} 逐字比这两处，不比结果（数据少的时候结果永远一致，看不出漂）。
     */
    String REPORT_WHERE = "<if test=\"st!=null and st!=''\">WHERE r.status=#{st}</if>";

    /**
     * 举报列表。带上报举人与被举报人的昵称（G2）：只有两个自增 id 的列表运营读不懂，
     * 每次都要再跳【用户】查一次人，等于把审核页变成只会点按钮的地方。
     * LEFT JOIN 而非 INNER：被举报人可以注销（{@code target_user} 置空），举报行必须照样看得见。
     *
     * <p>状态筛选从 {@code (#{st}='' OR status=#{st})} 改成动态拼 WHERE（G5）：那个 OR 让
     * {@code idx_status} 整个失效（优化器无法从"或者等于任意值"里推出范围），
     * 后台每次刷新都在排全表。不筛状态时本来就该是全表 + id 倒序，那是它真实的样子。
     */
    @Select("<script>SELECT r.id,r.reporter,r.target_user targetUser,r.kind,r.ref_id refId,r.reason,r.status," +
            "r.created_at createdAt,r.handled_by handledBy," +
            "ru.nickname reporterName,tu.nickname targetName," +
            "(r.target_user IS NULL AND r.kind IN ('nickname','behavior')) targetGone " +
            "FROM report r LEFT JOIN app_user ru ON ru.id=r.reporter LEFT JOIN app_user tu ON tu.id=r.target_user " +
            REPORT_WHERE + " " +
            "ORDER BY r.id DESC LIMIT #{size} OFFSET #{off}</script>")
    List<Map<String, Object>> pageReports(@Param("st") String st, @Param("size") int size, @Param("off") int off);

    /**
     * 举报总数（H6-3）：面板那个分页组件以前是拿本页长度<b>算</b>一个总数出来的，
     * 于是"最后一页"永远点不出东西。总数只有库知道，就让它回答。
     *
     * <p>这里刻意不带那两个 {@code LEFT JOIN}：两个 join 都按 {@code app_user.id}（主键）关联，
     * 既不会放大行数也不会丢行，所以去掉它们的结果与 {@link #pageReports} 完全一致，
     * 而 {@code COUNT(*)} 不用再走一遍那两张表。这一前提是
     * {@code MapperQueryHygieneTest#reportCountNeedsNoJoins} 钉住的东西。
     */
    @Select("<script>SELECT COUNT(*) FROM report r " + REPORT_WHERE + "</script>")
    long countReports(@Param("st") String st);

    @Insert("INSERT INTO report(reporter,target_user,kind,ref_id,reason) VALUES(#{reporter},#{target},#{kind},#{ref},#{reason})")
    int addReport(@Param("reporter") Long reporter, @Param("target") Long target,
                  @Param("kind") String kind, @Param("ref") String ref, @Param("reason") String reason);

    @Update("UPDATE report SET status=#{st}, handled_by=#{by} WHERE id=#{id}")
    int handleReport(@Param("id") long id, @Param("st") String st, @Param("by") String by);

    /**
     * 待处理条数（G2）：后台【审核】顶上的"待处理 N"接的就是这个真数。
     * 只数 {@code open}——已被同行处理过或驳回的不该再占用运营视线，
     * 否则这条数字只会单调增长，等于没有。
     */
    @Select("SELECT COUNT(*) FROM report WHERE status='open'")
    long openCount();

    /** 同一个人对同一个目标同一类事，只留一条在途（重复举报限流；e2e 判的就是这条）。 */
    @Select("SELECT COUNT(*) FROM report WHERE reporter=#{by} AND kind=#{kind} " +
            "AND IFNULL(ref_id,'')=#{ref} AND status='open'")
    long openDuplicate(@Param("by") long by, @Param("kind") String kind, @Param("ref") String ref);

    /** 每人每日举报上限的尺子：走 {@code chemera.guard.report-max}，防的是"把举报当刷屏工具"。 */
    @Select("SELECT COUNT(*) FROM report WHERE reporter=#{by} AND created_at >= CURDATE()")
    long countToday(long by);

    /** 被举报人是否真实存在（拒绝"举报一个不存在的 uid"这种空转工单）。 */
    @Select("SELECT COUNT(*) FROM app_user WHERE id=#{id}")
    long userExists(long id);

    /** 删号（G6）：他发起的举报是他的数据，跟着账号删。 */
    @Delete("DELETE FROM report WHERE reporter=#{uid}")
    int deleteReportsBy(long uid);

    /**
     * 删号（G6）：别人举报他的记录**不删**，只把 {@code target_user} 置空标成"已注销"。
     * 返回的是被标记的行数。理由见 {@code service/AccountPurge} 的类注释——
     * 删号不能顺手把针对自己的举报史一起洗掉。
     */
    @Update("UPDATE report SET target_user=NULL WHERE target_user=#{uid}")
    int unreportTarget(long uid);

    /**
     * 词表全量：只给 {@link com.chemera.server.game.SensitiveFilter} 用——判定命中要的是整张表，
     * 分页在这里等于漏词。面板那份走 {@link #pageWords}（H6-3）。
     */
    @Select("SELECT id,word,level FROM sensitive_word ORDER BY id")
    List<Map<String, Object>> words();

    /**
     * 词表面板列表（H6-3）：改成 {@code id DESC} 分页。
     *
     * <p>以前这个端点回整张表，面板也就没有分页——词表是运营一行一行加出来的，只会变长；
     * 而 {@link #words()} 那个 {@code ORDER BY id} 把最新的词排在最后一页，
     * 加完词想在面板上确认"我那条进去了"的人得翻到底。判定读的是全量，与这里的顺序无关。
     */
    @Select("SELECT id,word,level FROM sensitive_word ORDER BY id DESC LIMIT #{size} OFFSET #{off}")
    List<Map<String, Object>> pageWords(@Param("size") int size, @Param("off") int off);

    /** 词表总数：与 {@link #pageWords} 同一张表、同一句（都无 WHERE），所以页数与行数不会各说各话。 */
    @Select("SELECT COUNT(*) FROM sensitive_word")
    long countWords();

    @Insert("INSERT INTO sensitive_word(word,level) VALUES(#{word},#{level}) ON DUPLICATE KEY UPDATE level=VALUES(level)")
    int upsertWord(@Param("word") String word, @Param("level") int level);

    @Delete("DELETE FROM sensitive_word WHERE id=#{id}")
    int deleteWord(long id);

}
