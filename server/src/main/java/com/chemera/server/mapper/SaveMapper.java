package com.chemera.server.mapper;

import com.chemera.server.entity.UserSave;
import com.chemera.server.entity.UserSaveRevision;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SaveMapper {
    @Select("SELECT * FROM user_save WHERE user_id=#{id}")
    UserSave find(long id);

    @Insert("INSERT INTO user_save(user_id,payload,revision) VALUES(#{id},#{payload},1) " +
            "ON DUPLICATE KEY UPDATE payload=VALUES(payload), revision=revision+1")
    int upsert(@Param("id") long id, @Param("payload") String payload);

    @Select("SELECT revision FROM user_save WHERE user_id=#{id}")
    Long currentRevision(long id);

    @Insert("INSERT INTO user_save_revision(user_id,revision,payload,source) VALUES(#{userId},#{revision},#{payload},#{source})")
    int addRevision(UserSaveRevision r);

    @Select("SELECT * FROM user_save_revision WHERE user_id=#{id} ORDER BY revision DESC LIMIT 50")
    List<UserSaveRevision> listRevisions(long id);

    @Select("SELECT * FROM user_save_revision WHERE user_id=#{id} AND revision=#{rev}")
    UserSaveRevision findRevision(@Param("id") long id, @Param("rev") long rev);

    @Delete("DELETE FROM user_save_revision WHERE user_id=#{id} AND revision <= " +
            "(SELECT x FROM (SELECT MAX(revision)-30 x FROM user_save_revision WHERE user_id=#{id}) t)")
    int trimRevisions(long id);

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
