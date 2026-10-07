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

    /**
     * {@code last_login_at} 由调用方的时钟写，不用 SQL 的 {@code NOW()}：这一列正是按天统计的判据
     * （{@code AnalyticsMapper.ACTIVE_ON} 拿 Java 算出来的当天去比、{@code loginTrend} 按它分桶），
     * 写它的和被它判断的必须是同一个钟。详见 {@link SessionMapper} 里那条"谁判断，谁的时钟写"。
     */
    @Update("UPDATE app_user SET pass_hash=#{h}, last_login_at=#{now} WHERE id=#{id}")
    int updatePass(@Param("id") long id, @Param("h") String hash,
                   @Param("now") java.time.LocalDateTime now);

    default int updatePass(long id, String hash) {
        return updatePass(id, hash, java.time.LocalDateTime.now());
    }

    @Update("UPDATE app_user SET last_login_at=#{now} WHERE id=#{id}")
    int touchLogin(@Param("id") long id, @Param("now") java.time.LocalDateTime now);

    default int touchLogin(long id) {
        return touchLogin(id, java.time.LocalDateTime.now());
    }

    @Update("UPDATE app_user SET nickname=#{nick} WHERE id=#{id}")
    int updateNickname(@Param("id") long id, @Param("nick") String nick);

    @Update("UPDATE app_user SET status=#{st}, banned_until=#{until} WHERE id=#{id}")
    int setBan(@Param("id") long id, @Param("st") int status, @Param("until") java.time.LocalDateTime until);

    @Delete("DELETE FROM app_user WHERE id=#{id}")
    int delete(long id);

    /** 青少年模式标记：CurfewGuard 每次玩法请求都要问一次，所以只取这一列，别把整行读回来。 */
    @Select("SELECT minor FROM app_user WHERE id=#{id}")
    Integer minorOf(long id);

    @Update("UPDATE app_user SET minor=#{v} WHERE id=#{id}")
    int setMinor(@Param("id") long id, @Param("v") int v);

    /** TapTap 身份 → 本服账号。uk_taptap_open 保证一个第三方主体只对应一个账号。 */
    @Select("SELECT * FROM app_user WHERE taptap_open_id=#{oid}")
    AppUser findByTapTap(String oid);

    /** 第三方登录建档：没有口令（存一个随机串占位，玩家永远不可能用它登录），要改口令走后台重置。 */
    @Insert("INSERT INTO app_user(username,pass_hash,nickname,taptap_open_id,is_guest)" +
            " VALUES(#{username},#{passHash},#{nickname},#{taptapOpenId},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertExternal(AppUser u);

    /**
     * 后台【用户】那一条 WHERE 的唯一副本（G5）。
     *
     * <p>列名不加分隔符前缀是刻意的：{@code page()} join 了 user_save，但 user_save 里没有
     * username/nickname 这两列，所以不限定也不会歧义，而 count 与 page 就能共用同一个字符串。
     * 之前它们是两份手写文本，count 少了 nickname 那半句——于是按昵称搜索时"总数"是 0、
     * 列表里有 3 行，分页组件直接算错页数（迭代 2 的资产页踩过同形问题）。
     *
     * <p>{@code LIKE '%q%'} 用不上索引，这张表目前几百行；错了的数比慢的数危害大，所以先修一致性，
     * 真到需要全文检索再上 ngram 索引。
     */
    String LIST_FILTER = "(#{q}='' OR username LIKE CONCAT('%',#{q},'%') OR nickname LIKE CONCAT('%',#{q},'%'))";

    /**
     * 列表顺带带出金币/钻石：它们在 user_save.payload 里（JSON 列），不在 app_user 表，
     * 所以用 JSON_EXTRACT 定点取两个字段，而不是把整份存档读回来。没存档的游客/新号返回 null。
     *
     * <p>这一条 SQL 里出现的 {@code payload} 只允许在 JSON_EXTRACT 里当取值来源；
     * 把整份 payload 选进列表结果就是 G5 说的那处扫（几十份存档进 JVM，只为渲染一张表）。
     */
    @Select("SELECT u.id,u.username,u.nickname,u.status,u.is_guest,u.minor,u.taptap_open_id,u.created_at,u.last_login_at," +
            " CAST(JSON_UNQUOTE(JSON_EXTRACT(s.payload,'$.coins')) AS SIGNED) AS coins," +
            " CAST(JSON_UNQUOTE(JSON_EXTRACT(s.payload,'$.diamonds')) AS SIGNED) AS diamonds" +
            " FROM app_user u LEFT JOIN user_save s ON s.user_id=u.id " +
            "WHERE " + LIST_FILTER + " ORDER BY u.id DESC LIMIT #{size} OFFSET #{off}")
    List<Map<String, Object>> page(@Param("q") String q, @Param("size") int size, @Param("off") int off);

    @Select("SELECT COUNT(*) FROM app_user WHERE " + LIST_FILTER)
    long count(@Param("q") String q);
}
