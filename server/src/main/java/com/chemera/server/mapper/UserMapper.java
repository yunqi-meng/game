package com.chemera.server.mapper;

import com.chemera.server.entity.AppUser;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {
    @Select("SELECT * FROM app_user WHERE username=#{u}")
    AppUser findByUsername(String u);

    @Select("SELECT * FROM app_user WHERE id=#{id}")
    AppUser findById(long id);

    @Insert("INSERT INTO app_user(username,pass_hash,nickname) VALUES(#{username},#{passHash},#{nickname})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AppUser u);

    @Insert("INSERT INTO app_user(username,pass_hash,nickname,is_guest) VALUES(#{username},#{passHash},#{nickname},1)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGuest(AppUser u);

    @Update("UPDATE app_user SET pass_hash=#{h}, last_login_at=NOW() WHERE id=#{id}")
    int updatePass(@Param("id") long id, @Param("h") String hash);

    @Update("UPDATE app_user SET last_login_at=NOW() WHERE id=#{id}")
    int touchLogin(long id);

    @Update("UPDATE app_user SET nickname=#{nick} WHERE id=#{id}")
    int updateNickname(@Param("id") long id, @Param("nick") String nick);

    @Update("UPDATE app_user SET status=#{st}, banned_until=#{until} WHERE id=#{id}")
    int setBan(@Param("id") long id, @Param("st") int status, @Param("until") java.time.LocalDateTime until);

    @Delete("DELETE FROM app_user WHERE id=#{id}")
    int delete(long id);

    /**
     * 列表顺带带出金币/钻石：它们在 user_save.payload 里（JSON 列），不在 app_user 表，
     * 所以用 JSON_EXTRACT 定点取两个字段，而不是把整份存档读回来。没存档的游客/新号返回 null。
     */
    @Select("SELECT u.id,u.username,u.nickname,u.status,u.is_guest,u.created_at,u.last_login_at," +
            " CAST(JSON_UNQUOTE(JSON_EXTRACT(s.payload,'$.coins')) AS SIGNED) AS coins," +
            " CAST(JSON_UNQUOTE(JSON_EXTRACT(s.payload,'$.diamonds')) AS SIGNED) AS diamonds" +
            " FROM app_user u LEFT JOIN user_save s ON s.user_id=u.id " +
            "WHERE (#{q}='' OR u.username LIKE CONCAT('%',#{q},'%') OR u.nickname LIKE CONCAT('%',#{q},'%')) " +
            "ORDER BY u.id DESC LIMIT #{size} OFFSET #{off}")
    List<Map<String, Object>> page(@Param("q") String q, @Param("size") int size, @Param("off") int off);

    @Select("SELECT COUNT(*) FROM app_user WHERE (#{q}='' OR username LIKE CONCAT('%',#{q},'%'))")
    long count(@Param("q") String q);
}
