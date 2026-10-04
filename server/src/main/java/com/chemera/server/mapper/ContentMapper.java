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

    // 后台列表（返回精简列）
    @Select("<script>SELECT content_type contentType,item_id itemId,name,sort,enabled,updated_at updatedAt,updated_by updatedBy " +
            "FROM content_item WHERE content_type=#{type} " +
            "<if test=\"q!=null and q!=''\">AND (name LIKE CONCAT('%',#{q},'%') OR item_id LIKE CONCAT('%',#{q},'%'))</if> " +
            "ORDER BY sort LIMIT #{size} OFFSET #{off}</script>")
    List<java.util.Map<String, Object>> page(@Param("type") String type, @Param("q") String q,
                                             @Param("size") int size, @Param("off") int off);

    @Select("<script>SELECT COUNT(*) FROM content_item WHERE content_type=#{type} " +
            "<if test=\"q!=null and q!=''\">AND (name LIKE CONCAT('%',#{q},'%') OR item_id LIKE CONCAT('%',#{q},'%'))</if></script>")
    long count(@Param("type") String type, @Param("q") String q);

    @Select("SELECT * FROM content_item WHERE content_type=#{type} AND item_id=#{id}")
    ContentItem get(@Param("type") String type, @Param("id") String id);

    @Insert("INSERT INTO content_item(content_type,item_id,name,sort,enabled,data,updated_by) " +
            "VALUES(#{contentType},#{itemId},#{name},#{sort},#{enabled},#{data},#{updatedBy}) " +
            "ON DUPLICATE KEY UPDATE name=VALUES(name),sort=VALUES(sort),enabled=VALUES(enabled),data=VALUES(data),updated_by=VALUES(updated_by)")
    int upsert(ContentItem it);

    @Delete("DELETE FROM content_item WHERE content_type=#{type} AND item_id=#{id}")
    int delete(@Param("type") String type, @Param("id") String id);

    @Update("UPDATE content_item SET enabled=#{en}, updated_by=#{by} WHERE content_type=#{type} AND item_id=#{id}")
    int setEnabled(@Param("type") String type, @Param("id") String id, @Param("en") int en, @Param("by") String by);

    @Select("SELECT DISTINCT content_type FROM content_item ORDER BY content_type")
    List<String> types();
}
