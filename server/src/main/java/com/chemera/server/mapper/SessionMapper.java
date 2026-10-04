package com.chemera.server.mapper;

import com.chemera.server.entity.UserSession;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface SessionMapper {
    @Insert("INSERT INTO user_session(user_id,refresh_hash,device,expires_at) VALUES(#{userId},#{hash},#{device},#{exp})")
    int insert(@Param("userId") long userId, @Param("hash") String refreshHash,
               @Param("device") String device, @Param("exp") LocalDateTime exp);

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
