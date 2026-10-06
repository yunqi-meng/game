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

    public AccountPurge(UserMapper users, SessionMapper sessions, SaveMapper saves,
                        AnalyticsMapper analytics, AdTicketMapper tickets, ModerationMapper mod) {
        this.users = users; this.sessions = sessions; this.saves = saves;
        this.analytics = analytics; this.tickets = tickets; this.mod = mod;
    }

    /**
     * 逐表清理并回报"删了什么、删了几行"。这份数字要落进 {@code audit_log}：
     * 事后追责时"我确实清了他的存档"得有凭据，而不是一句口头承诺。
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
        return done;
    }
}
