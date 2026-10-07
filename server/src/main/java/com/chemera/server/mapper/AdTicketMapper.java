package com.chemera.server.mapper;

import com.chemera.server.entity.AdTicket;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface AdTicketMapper {

    /**
     * 签发落库：{@code issued_at} 由实体带进来（应用侧的意图时刻），不再靠列上的
     * {@code DEFAULT CURRENT_TIMESTAMP(3)}。
     *
     * <p>理由是"同一列只用一支钟"：这张表的 {@code expires_at} 本来就是 Java 写的，
     * {@code expireStale} 与回调里的过期判断也全用 Java 的钟；而 G8/运营面板那几个窗口
     * （{@link #statusCounts}/{@link #kindCounts}/{@link #purgeOld}）现在按应用侧的当天零点往回看。
     * 若 {@code issued_at} 继续由库钟盖，库与进程时区不一致时"近 7 天"的漏斗就会少掉半天，
     * 而玩家侧的"今天还能看几次"用的是另一套日子。内存台账 {@code MemAdTickets} 早就是 Java 盖的，
     * 这条改动只是让生产与回归同一句话。
     *
     * <p>{@code rewarded_at} 仍由 SQL 的 {@code NOW(3)} 盖：它是"回调那一刻"的取证戳，谁都不拿它做判断，
     * 与 {@code content_item}/{@code config} 上那两处 {@code ON UPDATE CURRENT_TIMESTAMP(3)} 同一类。
     */
    @Insert("INSERT INTO ad_ticket(ticket,user_id,kind,reward,amount,points,status,space_id,issued_at,expires_at) " +
            "VALUES(#{ticket},#{userId},#{kind},#{reward},#{amount},#{points},#{status},#{spaceId},#{issuedAt},#{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdTicket t);

    @Select("SELECT * FROM ad_ticket WHERE ticket=#{ticket}")
    AdTicket byTicket(String ticket);

    @Select("SELECT * FROM ad_ticket WHERE trans_id=#{transId}")
    AdTicket byTrans(String transId);

    /** 同一广告位是否还挂着未完成工单（issued/rewarded）：玩家一次只能欠一张。 */
    @Select("SELECT * FROM ad_ticket WHERE user_id=#{uid} AND kind=#{kind} AND status IN ('issued','rewarded') " +
            "ORDER BY id DESC LIMIT 1")
    AdTicket live(@Param("uid") long uid, @Param("kind") String kind);

    /** 乐观状态机：只有仍在 issued 的工单才允许被回调置为 rewarded，重复回调改不动（返回 0）。 */
    @Update("UPDATE ad_ticket SET status='rewarded', trans_id=#{transId}, rewarded_at=NOW(3) " +
            "WHERE id=#{id} AND status='issued'")
    int markRewarded(@Param("id") long id, @Param("transId") String transId);

    /** 已回调、等待结算进存档的行；按签发顺序处理。 */
    @Select("SELECT * FROM ad_ticket WHERE user_id=#{uid} AND status='rewarded' ORDER BY id")
    List<AdTicket> pending(long uid);

    /** 结算同样走状态转移，避免并发请求把一次奖励计两遍。 */
    @Update("UPDATE ad_ticket SET status='settled' WHERE id=#{id} AND status='rewarded'")
    int markSettled(long id);

    /**
     * 反悔边：结算那一帧没能落进存档，把工单退回 rewarded 等下一帧重发。
     * 只在"本轮刚 markSettled 成功、随后写盘失败"这一条路上调用，所以不存在把老奖励放出来两遍的窗口。
     */
    @Update("UPDATE ad_ticket SET status='rewarded' WHERE id=#{id} AND status='settled'")
    int requeueSettled(long id);

    /** 过期未回调的工单清成 expired：不占额度，也只是运营看得到"发了没回"的漏斗。 */
    @Update("UPDATE ad_ticket SET status='expired' WHERE status='issued' AND expires_at < #{now}")
    int expireStale(java.time.LocalDateTime now);

    /**
     * 状态漏斗：{@code since} 由调用方算（见下面的默认方法），与 {@code expireStale} 用同一支进程钟。
     *
     * <p>以前写 {@code issued_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)}：窗口起点是库钟的"今天 00:00"，
     * 而 {@code issued_at} 与过期判定用的是进程钟——运营在面板上看"近 7 天"时，库里若是 UTC、应用是 +08:00，
     * 最早那半天会被切掉，条数对不上玩家侧实际看到的漏斗。
     */
    @Select("SELECT status, COUNT(*) n FROM ad_ticket " +
            "WHERE issued_at >= #{since} GROUP BY status")
    List<Map<String, Object>> statusCounts(@Param("since") java.time.LocalDateTime since);

    default List<Map<String, Object>> statusCounts(int days) {
        return statusCounts(java.time.LocalDate.now().minusDays(days).atStartOfDay());
    }

    @Select("SELECT kind, COUNT(*) n FROM ad_ticket " +
            "WHERE issued_at >= #{since} GROUP BY kind ORDER BY n DESC")
    List<Map<String, Object>> kindCounts(@Param("since") java.time.LocalDateTime since);

    default List<Map<String, Object>> kindCounts(int days) {
        return kindCounts(java.time.LocalDate.now().minusDays(days).atStartOfDay());
    }

    @Select("SELECT COUNT(*) FROM ad_ticket WHERE user_id=#{uid} AND status IN ('settled','rewarded')")
    long settledCount(long uid);

    /**
     * 在途工单数（issued + rewarded）：G8 运维面板上的那个"pending"。
     *
     * <p>这个数字是广告链路的体检表——{@code issued} 堆着涨说明回调没回来（口令错了、平台侧没发、
     * 网络不通），{@code rewarded} 堆着涨说明玩家没再发意图、奖励结算不出去。两种都是"玩家看了广告没拿到钱"，
     * 而玩家自己是不会来告诉我们的。
     */
    @Select("SELECT COUNT(*) FROM ad_ticket WHERE status IN ('issued','rewarded')")
    long pendingCount();

    /** 清陈旧工单：保留窗口的起点由调用方算，与签发/过期用的同一支钟。 */
    @Delete("DELETE FROM ad_ticket WHERE status IN ('settled','expired','void') AND issued_at < #{before}")
    int purgeOld(@Param("before") java.time.LocalDateTime before);

    default int purgeOld(int keepDays) {
        return purgeOld(java.time.LocalDateTime.now().minusDays(keepDays));
    }

    /** 删号（G6）：工单带 uid、广告位与奖励明细，属于本人数据，跟着账号一起走。 */
    @Delete("DELETE FROM ad_ticket WHERE user_id=#{uid}")
    int purgeUser(long uid);
}
