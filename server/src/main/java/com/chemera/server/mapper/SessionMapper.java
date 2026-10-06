package com.chemera.server.mapper;

import com.chemera.server.entity.UserSession;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface SessionMapper {
    /**
     * 插入一行登录态并把自增主键回填到 {@code s.id}（A6 的锚点：访问令牌里的 sid 就是它）。
     *
     * <p>之所以从四个散参数改成传实体：{@code useGeneratedKeys} 需要一个能被写回 key 的参数对象，
     * 散参数没地方接。顺带也让"这一行对应哪个会话"这件事在调用点可见，而不是靠 insert 的返回值猜。
     */
    @Insert("INSERT INTO user_session(user_id,refresh_hash,device,expires_at)"
            + " VALUES(#{userId},#{refreshHash},#{device},#{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(UserSession s);

    /**
     * 这一次登录还活着吗：访问令牌校验用的每请求一问（主键 + 用户号，成本与后台侧回查同量级）。
     *
     * <p>{@code rotated_at IS NULL} 是刻意的：轮换掉的会话即便还在 15 秒宽限期内，也不该再让旧访问令牌
     * 继续通行——宽限期是给"刷新令牌几乎同时到"的多标签页留的，不是给旧会话续命留的。
     */
    @Select("SELECT COUNT(*) FROM user_session WHERE id=#{sid} AND user_id=#{uid}"
            + " AND revoked_at IS NULL AND rotated_at IS NULL AND expires_at>NOW()")
    int liveCount(@Param("sid") long sid, @Param("uid") long uid);

    /** 轮换/重用判定要看 revoked_at 与 rotated_at，所以不能只查有效行。 */
    @Select("SELECT * FROM user_session WHERE refresh_hash=#{hash} ORDER BY id DESC LIMIT 1")
    UserSession findByHash(String hash);

    @Update("UPDATE user_session SET revoked_at=NOW() WHERE refresh_hash=#{hash}")
    int revoke(String hash);

    /** 轮换作废：与主动注销区分开，前者留极短宽限给多标签页并发刷新。 */
    @Update("UPDATE user_session SET rotated_at=NOW() WHERE refresh_hash=#{hash} AND rotated_at IS NULL AND revoked_at IS NULL")
    int rotate(String hash);

    @Update("UPDATE user_session SET revoked_at=NOW() WHERE user_id=#{id} AND revoked_at IS NULL")
    int revokeAll(long id);

    @Select("SELECT COUNT(*) FROM user_session WHERE revoked_at IS NULL AND rotated_at IS NULL AND expires_at>NOW()")
    long countActive();

    /** 注销账号：物理删除登录态行（刷新令牌只存摘要，删除即彻底不可用）。 */
    @Delete("DELETE FROM user_session WHERE user_id=#{id}")
    int purge(long id);

    /** 日常清理：过期的即时删，已撤销/已轮换的留一段追溯窗口供重用取证。 */
    @Delete("DELETE FROM user_session WHERE expires_at<#{expiredBefore}"
            + " OR (revoked_at IS NOT NULL AND revoked_at<#{revokedBefore})"
            + " OR (rotated_at IS NOT NULL AND rotated_at<#{revokedBefore})")
    int purgeStale(@Param("expiredBefore") LocalDateTime expiredBefore,
                   @Param("revokedBefore") LocalDateTime revokedBefore);
}
