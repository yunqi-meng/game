package com.chemera.server.game;

import com.chemera.server.entity.AdTicket;
import com.chemera.server.mapper.AdTicketMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 生产实现：ad_ticket 表。状态转移全部用条件 UPDATE，靠数据库的行锁保证一次观看只算一次。 */
@Service
public class DbAdTicketStore implements AdTicketStore {
    private final AdTicketMapper tickets;

    public DbAdTicketStore(AdTicketMapper tickets) { this.tickets = tickets; }

    @Override
    public void issue(AdTicket t) { tickets.insert(t); }

    @Override
    public AdTicket byTicket(String ticket) { return tickets.byTicket(ticket); }

    @Override
    public AdTicket live(long uid, String kind) { return tickets.live(uid, kind); }

    @Override
    public boolean markRewarded(long id, String transId) { return tickets.markRewarded(id, transId) == 1; }

    @Override
    public List<AdTicket> pending(long uid) { return tickets.pending(uid); }

    @Override
    public boolean markSettled(long id) { return tickets.markSettled(id) == 1; }

    @Override
    public boolean requeueSettled(long id) { return tickets.requeueSettled(id) == 1; }

    @Override
    public int expireStale(LocalDateTime now) { return tickets.expireStale(now); }
}
