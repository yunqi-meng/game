package com.chemera.server.mapper;

import com.chemera.server.entity.ContentItem;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ContentMapper {
    @Select("SELECT version FROM content_version WHERE id=1")
    long version();

    @Update("UPDATE content_version SET version=version+1 WHERE id=1")
    int bumpVersion();

    // 下发给客户端：所有启用的内容
    @Select("SELECT content_type, item_id, data FROM content_item WHERE enabled=1 ORDER BY content_type, sort")
    List<ContentItem> allEnabled();

    /**
     * 后台列表的筛选条件（H6-3）：总数与列表引用<b>同一段文本</b>，理由同
     * {@link ModerationMapper#REPORT_WHERE}——两处手写迟早漂一份。
     *
     * <p>排序补了 {@code ,item_id} 这一记 tiebreak：{@code sort} 不唯一（同一类型里一堆行都是 0），
     * 而 {@code LIMIT/OFFSET} 只保证"按这个顺序的第 N 到 M 行"，顺序本身不唯一时同一行可以在
     * 第 1 页和第 2 页各出现一次、另一行两页都不出现。面板逐页翻下去会看到重复行、
     * 而"共 N 条"对不上——这不是显示问题，是分页本身不稳定。
     */
    String LIST_WHERE = "WHERE content_type=#{type} " +
            "<if test=\"q!=null and q!=''\">AND (name LIKE CONCAT('%',#{q},'%') OR item_id LIKE CONCAT('%',#{q},'%'))</if>";

    // 后台列表（返回精简列）
    @Select("<script>SELECT content_type contentType,item_id itemId,name,sort,enabled,updated_at updatedAt,updated_by updatedBy " +
            "FROM content_item " + LIST_WHERE + " " +
            "ORDER BY sort,item_id LIMIT #{size} OFFSET #{off}</script>")
    List<java.util.Map<String, Object>> page(@Param("type") String type, @Param("q") String q,
                                             @Param("size") int size, @Param("off") int off);

    @Select("<script>SELECT COUNT(*) FROM content_item " + LIST_WHERE + "</script>")
    long count(@Param("type") String type, @Param("q") String q);

    @Select("SELECT * FROM content_item WHERE content_type=#{type} AND item_id=#{id}")
    ContentItem get(@Param("type") String type, @Param("id") String id);

    @Insert("INSERT INTO content_item(content_type,item_id,name,sort,enabled,data,updated_by) " +
            "VALUES(#{contentType},#{itemId},#{name},#{sort},#{enabled},#{data},#{updatedBy}) " +
            "ON DUPLICATE KEY UPDATE name=VALUES(name),sort=VALUES(sort),enabled=VALUES(enabled),data=VALUES(data),updated_by=VALUES(updated_by)")
    int upsert(ContentItem it);

    /**
     * 只插入（H6-2）：面板认为这一行还不存在时用。撞上主键就抛 DuplicateKeyException，
     * 控制器翻成 409——那是"别人在你填表的时候把这条建出来了"，用 {@link #upsert} 等于直接抹掉对方那份。
     */
    @Insert("INSERT INTO content_item(content_type,item_id,name,sort,enabled,data,updated_by) " +
            "VALUES(#{i.contentType},#{i.itemId},#{i.name},#{i.sort},#{i.enabled},#{i.data},#{i.updatedBy})")
    int insertOnly(@Param("i") ContentItem i);

    /**
     * 条件更新（乐观锁的那半边）：只有 {@code updated_at} 仍是面板拿到的那一版才写，
     * 时间戳交给列上的 {@code ON UPDATE CURRENT_TIMESTAMP(3)}（{@code V1__baseline}），
     * 不由 Java 侧盖——两笔并发写谁先落库，应以库的顺序为准。返回 0 即"这一行不是你看到的那一行了"。
     */
    @Update("UPDATE content_item SET name=#{i.name},sort=#{i.sort},enabled=#{i.enabled},data=#{i.data},updated_by=#{i.updatedBy} " +
            "WHERE content_type=#{i.contentType} AND item_id=#{i.itemId} AND updated_at=#{expect}")
    int updateIfUnchanged(@Param("i") ContentItem i, @Param("expect") java.time.LocalDateTime expect);

    /** 条件删除：别把别人刚改好的那一版删掉。 */
    @Delete("DELETE FROM content_item WHERE content_type=#{type} AND item_id=#{id} AND updated_at=#{expect}")
    int deleteIfUnchanged(@Param("type") String type, @Param("id") String id,
                          @Param("expect") java.time.LocalDateTime expect);

    /** 条件上下架（面板列表里那个开关）：形状同 {@link #updateIfUnchanged}。 */
    @Update("UPDATE content_item SET enabled=#{en}, updated_by=#{by} " +
            "WHERE content_type=#{type} AND item_id=#{id} AND updated_at=#{expect}")
    int setEnabledIfUnchanged(@Param("type") String type, @Param("id") String id, @Param("en") int en,
                              @Param("by") String by, @Param("expect") java.time.LocalDateTime expect);

    @Delete("DELETE FROM content_item WHERE content_type=#{type} AND item_id=#{id}")
    int delete(@Param("type") String type, @Param("id") String id);

    @Update("UPDATE content_item SET enabled=#{en}, updated_by=#{by} WHERE content_type=#{type} AND item_id=#{id}")
    int setEnabled(@Param("type") String type, @Param("id") String id, @Param("en") int en, @Param("by") String by);

    @Select("SELECT DISTINCT content_type FROM content_item ORDER BY content_type")
    List<String> types();
}
