package com.chemera.server.game;

import com.chemera.server.entity.AdTicket;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存工单台账：只为回归服务，语义与 {@link DbAdTicketStore} 逐条对齐。
 * 三个状态转移都是"条件更新"（改了才返回 true），并发只算一次的保证就来自这里，所以测试必须复刻它。
 */
class MemAdTickets implements AdTicketStore {

    private final List<AdTicket> rows = new ArrayList<>();
    private long seq = 0;

    /** 强制让下一次 markRewarded/markSettled 失败，用来验证并发/重复回调不会双发。 */
    boolean refuseTransition = false;

    @Override
    public synchronized void issue(AdTicket t) {
        t.setId(++seq);
        if (t.getIssuedAt() == null) t.setIssuedAt(LocalDateTime.now());
        rows.add(t);
    }

    @Override
    public synchronized AdTicket byTicket(String ticket) {
        if (ticket == null) return null;
        for (AdTicket t : rows) if (ticket.equals(t.getTicket())) return copy(t);
        return null;
    }

    @Override
    public synchronized AdTicket live(long uid, String kind) {
        AdTicket hit = null;
        for (AdTicket t : rows) {
            if (t.getUserId() == null || t.getUserId() != uid || !kind.equals(t.getKind())) continue;
            if (!AdService.ISSUED.equals(t.getStatus()) && !AdService.REWARDED.equals(t.getStatus())) continue;
            if (hit == null || t.getId() > hit.getId()) hit = t;
        }
        return hit == null ? null : copy(hit);
    }

    @Override
    public synchronized boolean markRewarded(long id, String transId) {
        AdTicket t = find(id);
        if (t == null || !AdService.ISSUED.equals(t.getStatus()) || refuseTransition) return false;
        if (transIdAlreadyUsed(transId)) return false;
        t.setStatus(AdService.REWARDED);
        t.setTransId(transId);
        t.setRewardedAt(LocalDateTime.now());
        return true;
    }

    @Override
    public synchronized List<AdTicket> pending(long uid) {
        List<AdTicket> out = new ArrayList<>();
        for (AdTicket t : rows)
            if (t.getUserId() != null && t.getUserId() == uid && AdService.REWARDED.equals(t.getStatus())) out.add(copy(t));
        return out;
    }

    @Override
    public synchronized boolean markSettled(long id) {
        AdTicket t = find(id);
        if (t == null || !AdService.REWARDED.equals(t.getStatus()) || refuseTransition) return false;
        t.setStatus(AdService.SETTLED);
        settleClaims++;
        return true;
    }

    /**
     * 反悔边，和 {@link #markSettled} 一样只认当前状态：已经退回一次的工单不可能再退第二次。
     * settleClaims／requeueClaims 让回归能直接断言"这一帧到底抢到几个、又退回去几个"，
     * 不用去猜状态字符串。
     */
    @Override
    public synchronized boolean requeueSettled(long id) {
        AdTicket t = find(id);
        if (t == null || !AdService.SETTLED.equals(t.getStatus())) return false;
        t.setStatus(AdService.REWARDED);
        requeueClaims++;
        return true;
    }

    /** 成功的 rewarded→settled 次数（真发奖的次数）。 */
    int settleClaims;
    /** 成功的 settled→rewarded 次数（随作废帧退回、等着重发的次数）。 */
    int requeueClaims;

    @Override
    public synchronized int expireStale(LocalDateTime now) {
        int n = 0;
        for (AdTicket t : rows) {
            if (AdService.ISSUED.equals(t.getStatus()) && t.getExpiresAt() != null && t.getExpiresAt().isBefore(now)) {
                t.setStatus(AdService.EXPIRED);
                n++;
            }
        }
        return n;
    }

    /** 台账快照，供断言直接看状态。 */
    synchronized List<AdTicket> all() {
        List<AdTicket> out = new ArrayList<>();
        for (AdTicket t : rows) out.add(copy(t));
        return out;
    }

    private boolean transIdAlreadyUsed(String transId) {
        if (transId == null) return false;
        for (AdTicket t : rows) if (transId.equals(t.getTransId())) return true;
        return false;
    }

    private AdTicket find(long id) {
        for (AdTicket t : rows) if (t.getId() != null && t.getId() == id) return t;
        return null;
    }

    /** 返回副本，避免调用方拿着引用改状态绕过条件更新。 */
    private static AdTicket copy(AdTicket s) {
        AdTicket t = new AdTicket();
        t.setId(s.getId());
        t.setTicket(s.getTicket());
        t.setUserId(s.getUserId());
        t.setKind(s.getKind());
        t.setReward(s.getReward());
        t.setAmount(s.getAmount());
        t.setPoints(s.getPoints());
        t.setStatus(s.getStatus());
        t.setTransId(s.getTransId());
        t.setSpaceId(s.getSpaceId());
        t.setIssuedAt(s.getIssuedAt());
        t.setExpiresAt(s.getExpiresAt());
        t.setRewardedAt(s.getRewardedAt());
        return t;
    }
}
