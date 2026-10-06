package com.chemera.server.game;

import com.chemera.server.entity.AdTicket;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 激励视频工单的存储抽象：生产走 MySQL（{@link DbAdTicketStore}），单测用内存实现。
 * 状态机只有三条前进转移：issued→rewarded（回调验签通过）、rewarded→settled（结算进存档）、issued→expired（超时未回调），
 * 外加一条反悔边 settled→rewarded（{@link #requeueSettled}：结算那一帧没写进存档，奖励得留着下次再发）。
 * 每条都用"仅在当前状态才更新"的乐观写法，保证重复回调与并发结算都只能成功一次。
 */
public interface AdTicketStore {
    void issue(AdTicket t);

    AdTicket byTicket(String ticket);

    /** 同一位是否还有未完成（issued/rewarded）的工单：一次只允许挂一张，堵住"先囤券再刷回调"。 */
    AdTicket live(long uid, String kind);

    /** 只有仍为 issued 的工单才能被置为 rewarded。 */
    boolean markRewarded(long id, String transId);

    List<AdTicket> pending(long uid);

    boolean markSettled(long id);

    /**
     * 结算写盘失败后的反悔：把刚 {@code markSettled} 的工单退回 {@code rewarded}，下一帧重新到账。
     *
     * <p>没有它，"广告看完 → 工单置 settled → 存档写盘失败"这条链就是永久丢奖励：
     * 钱记在一份作废的帧里，工单却已经不算待结算了，玩家只能找客服。
     * 同样只认当前状态，所以并发下重复调用不会把已经 settled 过一次的奖励放出来第二遍。
     */
    boolean requeueSettled(long id);

    int expireStale(LocalDateTime now);
}
