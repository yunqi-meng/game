package com.chemera.server.mapper;

import com.chemera.server.entity.UserSave;
import com.chemera.server.entity.UserSaveRevision;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SaveMapper {
    @Select("SELECT * FROM user_save WHERE user_id=#{id}")
    UserSave find(long id);

    /**
     * 无条件写回并把版本号加一（后台改档／整档上传这条路径用；意图写回走 {@link #casPut}）。
     *
     * <p>{@code LAST_INSERT_ID(...)} 不是炫技而是把一次回表读省掉（G8）：这一句把"我这次推到了几号"
     * 塞进连接自己的会话变量，紧接着的 {@link #lastRevision()} 就能原样读回来，
     * 不必再去 {@code user_save} 上问一遍。少一次读还是次要的，主要是**不再可能读到别人的号**——
     * 以前 upsert 之后再 {@code SELECT revision}，中间要是并发写了一次，历史表里就会存进一个
     * 根本不存在的版本号，出事时对不上账。
     */
    @Insert("INSERT INTO user_save(user_id,payload,revision) VALUES(#{id},#{payload},LAST_INSERT_ID(1)) " +
            "ON DUPLICATE KEY UPDATE payload=VALUES(payload), revision=LAST_INSERT_ID(revision+1)")
    int upsert(@Param("id") long id, @Param("payload") String payload);

    /**
     * 上一条 {@link #upsert} 把 {@code revision} 推到了几号。走的是连接会话内存，不碰任何表，
     * 所以只能在**同一个事务里紧跟着** upsert 调用（{@code SaveService} 的两个写入口都满足）。
     */
    @Select("SELECT LAST_INSERT_ID()")
    long lastRevision();

    /**
     * CAS 写回的"建行"半边：存档行不存在时以 revision=1 建出来。
     * 用 INSERT IGNORE 而不是普通 INSERT，是因为同一个玩家的两条意图可能同时走到"发现没有行"这一步，
     * 撞主键的那条必须安静地返回 0，让调用方改走 {@link #casPut} 判冲突，而不是抛一片 500 给玩家。
     */
    @Insert("INSERT IGNORE INTO user_save(user_id,payload,revision) VALUES(#{id},#{payload},1)")
    int insertFirst(@Param("id") long id, @Param("payload") String payload);

    /**
     * 乐观并发写回：只有库里仍然是 {@code base} 这一帧时才落盘并把号加一。
     * 返回 0 表示"我读完之后已经有人写过了"，调用方必须重读重放，不能硬覆盖。
     */
    @Update("UPDATE user_save SET payload=#{payload}, revision=revision+1 " +
            "WHERE user_id=#{id} AND revision=#{base}")
    int casPut(@Param("id") long id, @Param("payload") String payload, @Param("base") long base);

    @Select("SELECT revision FROM user_save WHERE user_id=#{id}")
    Long currentRevision(long id);

    @Insert("INSERT INTO user_save_revision(user_id,revision,payload,source) VALUES(#{userId},#{revision},#{payload},#{source})")
    int addRevision(UserSaveRevision r);

    /**
     * 【历史】抽屉要的那几列（G5）：哪一版、什么时候、什么来源、多大，<b>加上由 MySQL 数出来的字节数</b>。
     *
     * <p>以前这个列表走的是 {@code SELECT * ORDER BY revision DESC LIMIT 50}，把最多 50 份完整存档
     * 搬进 JVM，只为了在表格里显示一个"几 KB"——打开一次历史抽屉等于把那 50 份 payload 读两遍
     * （一遍查询、一遍序列化）。{@code LENGTH(payload)} 数的是库里自己的字节，不用搬。
     *
     * <p>"列表不返回 payload"这条现在由 {@code MapperQueryHygieneTest} 钉住：注解文本里除了
     * {@code LENGTH(payload)} 不许出现 payload。存档回滚要读原文，那是 {@link #findRevision}
     * 单条取的事。
     */
    @Select("SELECT revision,source,created_at createdAt,LENGTH(payload) bytes " +
            "FROM user_save_revision WHERE user_id=#{id} ORDER BY revision DESC LIMIT 50")
    List<java.util.Map<String, Object>> listRevisionMeta(long id);

    @Select("SELECT * FROM user_save_revision WHERE user_id=#{id} AND revision=#{rev}")
    UserSaveRevision findRevision(@Param("id") long id, @Param("rev") long rev);

    /**
     * 修剪历史：只保留最近 {@code KEEP_REVISIONS} 个版本的量，加上<b>第 1 版那一行永远不删</b>。
     *
     * <p>{@code revision > 1} 不是保险丝而是必需的：存档改成抽样之后（{@code SaveService.historyWorth}，
     * 每 25 版记一条），"最早的哪一版还在"完全取决于这条 DELETE。少了它，账号一越过 31 版就把初始帧删掉，
     * 后台【回滚到最早一版】就会退到"不知道哪一版"（本轮 e2e 第一次跑就抓到这个：金币退回的不是 5000 而是中途的 5084），
     * 而玩家侧【历史】里"我开局是什么样"也没了。抽样的本意是删重复帧，不是删锚点。
     *
     * <p>为什么是范围删而不是"子查询里算 MAX 再减 30"（G8）：以前那句是同一张表上的相关子查询，
     * InnoDB 要在删除语句里先把它自己扫一遍求 MAX，命中 {@code idx_user_rev} 也要走一次聚合，
     * 而且删的是"库里最大号往前 30"。改成把号从外面递进来之后，写入方本来就知道自己刚写到几号
     * （{@code SaveService.record} 的 {@code rev} 参数），这一句就退化成一条走索引的范围 DELETE；
     * 抽样的年代这两种口径筛出来的行其实是同一批——历史表里存在的号本来就是稀疏的抽样点。
     */
    @Delete("DELETE FROM user_save_revision WHERE user_id=#{id} AND revision > 1 AND revision <= #{before}")
    int trimRevisionsBefore(@Param("id") long id, @Param("before") long before);

    /** 游客转正：存档与历史整体换主（新账号必然无存档，不会撞主键）。 */
    @Update("UPDATE user_save SET user_id=#{to} WHERE user_id=#{from}")
    int reassignUser(@Param("from") long from, @Param("to") long to);

    @Update("UPDATE user_save_revision SET user_id=#{to} WHERE user_id=#{from}")
    int reassignRevisions(@Param("from") long from, @Param("to") long to);

    /** 注销账号：物理删除该用户名下存档与历史（表间无外键，必须显式清）。 */
    @Delete("DELETE FROM user_save WHERE user_id=#{id}")
    int purgeUser(long id);

    @Delete("DELETE FROM user_save_revision WHERE user_id=#{id}")
    int purgeRevisions(long id);
}
