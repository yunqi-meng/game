package com.chemera.server.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 内容历史（G3）。
 *
 * <p>这一层的存在理由只有一条：{@code content_item} 是覆盖写的，没有这里就没有"上一版"。
 * 表形状照 {@code user_save_revision} 抄，语义相反——存的是<b>被顶掉之前</b>的那一版，
 * 理由写在 V11 的头注释里。
 */
@Mapper
public interface ContentRevisionMapper {

    @Insert("INSERT INTO content_revision(content_type,item_id,name,sort,enabled,data_json,version,operator,source)" +
            " VALUES(#{contentType},#{itemId},#{name},#{sort},#{enabled},#{dataJson},#{version},#{operator},#{source})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(com.chemera.server.entity.ContentRevision r);

    /**
     * 【历史】抽屉的列表：只有号、时间、谁、因为什么动作、多大，<b>没有 data_json</b>。
     *
     * <p>字节数交给 {@code LENGTH(data_json)} 在库里数——和存档侧那条是同一个理由（G5）：
     * 一个内容项最多 50 版，打开抽屉就把 50 份完整记录搬进 JVM，只为表格里那个"几 KB"。
     * 这条纪律现在由 {@code MapperQueryHygieneTest} 钉着，方法名以 list 开头不是随手起的。
     */
    @Select("SELECT id,content_type contentType,item_id itemId,name,sort,enabled,version,operator,source," +
            "created_at createdAt,LENGTH(data_json) bytes " +
            "FROM content_revision WHERE content_type=#{type} AND item_id=#{id} " +
            "ORDER BY id DESC LIMIT #{limit}")
    List<Map<String, Object>> listMeta(@Param("type") String type, @Param("id") String id,
                                       @Param("limit") int limit);

    /** 单条取原文：回滚要把整份 data 写回去，这是唯一允许带 data_json 出来的读法。 */
    @Select("SELECT id,content_type contentType,item_id itemId,name,sort,enabled,data_json dataJson," +
            "version,operator,source,created_at createdAt " +
            "FROM content_revision WHERE id=#{id}")
    com.chemera.server.entity.ContentRevision get(long id);

    /**
     * 第 {@code keep} 新的那一行的号（修剪用）。返回 null 表示历史还没攒到上限，一条都不该删。
     *
     * <p>修剪分两步而不是"DELETE ... WHERE id < (子查询同一张表)"：后者要么撞 MySQL 的 1093，
     * 要么靠套一层派生表来绕过，而 {@code SaveMapper.trimRevisionsBefore} 已经验证过
     * "号从外面递进来 + 一条范围删"更好读，这里沿用同一个形状。
     */
    @Select("SELECT id FROM content_revision WHERE content_type=#{type} AND item_id=#{id}" +
            " ORDER BY id DESC LIMIT 1 OFFSET #{offset}")
    Long floorId(@Param("type") String type, @Param("id") String id, @Param("offset") int offset);

    @Delete("DELETE FROM content_revision WHERE content_type=#{type} AND item_id=#{id} AND id < #{floor}")
    int deleteOlder(@Param("type") String type, @Param("id") String id, @Param("floor") long floor);

    /** 内容项被彻底废弃后不留孤儿历史：按项清（后台目前没有这个入口，先给注销/清理路径留着）。 */
    @Delete("DELETE FROM content_revision WHERE content_type=#{type} AND item_id=#{id}")
    int purgeItem(@Param("type") String type, @Param("id") String id);
}
