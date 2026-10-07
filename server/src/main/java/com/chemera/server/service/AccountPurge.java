package com.chemera.server.service;

import com.chemera.server.mapper.AdTicketMapper;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.mapper.ModerationMapper;
import com.chemera.server.mapper.SaveMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 删号（G6）：把"一个账号在这个库里留下的东西"一次性清干净，玩家自助注销与后台删号**共用这一套**。
 *
 * <p>为什么要收成一处：商店与个保法要的是"用户要求删除账号即删除全部数据"，而这条路以前有两个入口、
 * 两种语义——{@code AuthService.deleteAccount} 清了会话/存档/历史/埋点，后台
 * {@code DELETE /admin/api/users} 只删了 {@code app_user} 一行。走后台删掉的那批人，
 * 存档还在库里（连 payload 里的金币钻石一起），过两天用同名注册还能撞上"残留档"。
 * 两个入口语义不同本身就是坑，所以这里只有一份清单。
 *
 * <p>清单里除了原来的四项，还补了 {@code ad_ticket}（激励视频工单带 uid 与奖励明细，属于本人数据）
 * 与举报表：
 * <ul>
 *   <li><b>他发起的举报</b>删掉——那是他名下的数据；</li>
 *   <li><b>别人举报他的记录</b>只把 {@code target_user} 置空、行留着。删号不该能"洗白自己的举报史"：
 *       如果一个人被举报了违规昵称，注销就把审核面上那条线索一起带走，运营再也无从判断
 *       同名重建的那个号是不是同一位。置空之后后台看到的是"被举报人已注销"，理由文本还在。</li>
 * </ul>
 *
 * <p>{@code @Transactional} 是刻意的：删一半（清了存档、用户行还在）会留下一个"能登录但进度全空"的号，
 * 比删失败更难看。整笔回滚时调用方会拿到异常，运营看得见。
 */
@Service
public class AccountPurge {

    private final UserMapper users;
    private final SessionMapper sessions;
    private final SaveMapper saves;
    private final AnalyticsMapper analytics;
    private final AdTicketMapper tickets;
    private final ModerationMapper mod;
    /** 内存现场的清理方（G7）：库删完之后接着把这一位留在各进程内 map 里的格子抹掉。 */
    private final AccountMemoryEvictor memory;

    /**
     * Spring 装配入口：容器把<b>所有</b> {@link AccountMemoryEvictor} bean 收成一列注进来。
     * 六参那个构造只给单测与 {@code AuthService} 的测试装配用，所以必须显式标注首选构造。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AccountPurge(UserMapper users, SessionMapper sessions, SaveMapper saves,
                        AnalyticsMapper analytics, AdTicketMapper tickets, ModerationMapper mod,
                        List<AccountMemoryEvictor> memory) {
        this(users, sessions, saves, analytics, tickets, mod, compose(memory));
    }

    /** 把所有实现串成一个：某一个抛了不影响其余参与（清内存这件事是尽力而为，不许把删号事务带崩）。 */
    private static AccountMemoryEvictor compose(List<AccountMemoryEvictor> memory) {
        List<AccountMemoryEvictor> all = memory == null ? List.of() : List.copyOf(memory);
        if (all.isEmpty()) return AccountMemoryEvictor.NONE;
        if (all.size() == 1) return all.get(0);
        return uid -> {
            for (AccountMemoryEvictor e : all) {
                try {
                    e.evict(uid);
                } catch (RuntimeException ex) {
                    org.slf4j.LoggerFactory.getLogger(AccountPurge.class)
                            .warn("删号后清内存现场失败（库已删干净，这一步交给 TTL 收尾）uid={}: {}", uid, ex.toString());
                }
            }
        };
    }

    /** 没有内存清理方时的口径（老单测、{@code AuthService} 测试装配）：只删库，进程内让 TTL 自己收尾。 */
    public AccountPurge(UserMapper users, SessionMapper sessions, SaveMapper saves,
                        AnalyticsMapper analytics, AdTicketMapper tickets, ModerationMapper mod) {
        this(users, sessions, saves, analytics, tickets, mod, AccountMemoryEvictor.NONE);
    }

    public AccountPurge(UserMapper users, SessionMapper sessions, SaveMapper saves,
                        AnalyticsMapper analytics, AdTicketMapper tickets, ModerationMapper mod,
                        AccountMemoryEvictor memory) {
        this.users = users; this.sessions = sessions; this.saves = saves;
        this.analytics = analytics; this.tickets = tickets; this.mod = mod;
        this.memory = memory == null ? AccountMemoryEvictor.NONE : memory;
    }

    /**
     * 逐表清理并回报"删了什么、删了几行"。这份数字要落进 {@code audit_log}：
     * 事后追责时"我确实清了他的存档"得有凭据，而不是一句口头承诺。
     *
     * <p>最后一步是清内存现场：库里的东西删干净之后，进程内那两个按 uid 存的 map
     * （玩法临时台、青少年模式判定）也该跟着走，否则"注销再同名重建"会撞上旧号那一格现场。
     * 时机选在全部 SQL 都成功之后、事务提交之前——万一事务回滚，多清一次内存是无害的
     * （下一次请求会从库里那一帧重新装载），反过来（先清内存、库没删成）才会留下"号还在、现场没了"的怪相。
     */
    @Transactional
    public Map<String, Object> purge(long uid) {
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("sessions", sessions.purge(uid));
        done.put("save", saves.purgeUser(uid));
        done.put("saveRevisions", saves.purgeRevisions(uid));
        done.put("analytics", analytics.purgeUser(uid));
        done.put("adTickets", tickets.purgeUser(uid));
        done.put("reportsFiled", mod.deleteReportsBy(uid));
        done.put("reportsAbout", mod.unreportTarget(uid));
        done.put("user", users.delete(uid));
        memory.evict(uid);
        return done;
    }
}
