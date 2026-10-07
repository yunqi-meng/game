package com.chemera.server.service;

import com.chemera.server.mapper.AdTicketMapper;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.mapper.ModerationMapper;
import com.chemera.server.mapper.SaveMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 删号清单（G6）。
 *
 * <p>商店与个保法要的是"用户要求删除账号即删除全部数据"，而这条通道以前有两个入口、两种语义：
 * {@code AuthService.deleteAccount} 清了会话/存档/历史/埋点，后台 {@code DELETE /admin/api/users}
 * 只删 {@code app_user} 一行——走后台删掉的那批人，存档连里面的金币钻石还留在库里，
 * 过两天用同名注册还能撞上"残留档"。所以这份清单只有一份，两个入口都必须走它。
 *
 * <p>这里 mock 六张 Mapper，数的正是"哪几张表被动了、按什么顺序、动了谁的行"。
 * 真库效果由 e2e 的【后台删号】那一组逐表查空来兜。
 */
class AccountPurgeTest {

    private static AccountPurge purge(UserMapper users, SessionMapper sessions, SaveMapper saves,
                                      AnalyticsMapper analytics, AdTicketMapper tickets, ModerationMapper mod) {
        return new AccountPurge(users, sessions, saves, analytics, tickets, mod);
    }

    @Test
    void everyTableThatHoldsSomethingIsSwept() {
        UserMapper users = mock(UserMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        SaveMapper saves = mock(SaveMapper.class);
        AnalyticsMapper analytics = mock(AnalyticsMapper.class);
        AdTicketMapper tickets = mock(AdTicketMapper.class);
        ModerationMapper mod = mock(ModerationMapper.class);
        when(sessions.purge(7L)).thenReturn(3);
        when(saves.purgeUser(7L)).thenReturn(1);
        when(saves.purgeRevisions(7L)).thenReturn(25);
        when(analytics.purgeUser(7L)).thenReturn(9);
        when(tickets.purgeUser(7L)).thenReturn(2);
        when(mod.deleteReportsBy(7L)).thenReturn(1);
        when(mod.unreportTarget(7L)).thenReturn(4);
        when(users.delete(7L)).thenReturn(1);

        Map<String, Object> done = purge(users, sessions, saves, analytics, tickets, mod).purge(7L);

        // 六张表一个都不能漏：漏一张就是"删了号但数据还在"，而这正是商店审核挑出来的那件事
        verify(sessions).purge(7L);
        verify(saves).purgeUser(7L);
        verify(saves).purgeRevisions(7L);
        verify(analytics).purgeUser(7L);
        verify(tickets).purgeUser(7L);
        verify(mod).deleteReportsBy(7L);
        verify(mod).unreportTarget(7L);
        verify(users).delete(7L);

        assertEquals(3, done.get("sessions"));
        assertEquals(1, done.get("save"));
        assertEquals(25, done.get("saveRevisions"));
        assertEquals(9, done.get("analytics"));
        assertEquals(2, done.get("adTickets"));
        assertEquals(1, done.get("reportsFiled"));
        assertEquals(4, done.get("reportsAbout"));
        assertEquals(1, done.get("user"));
    }

    /**
     * 他发起的举报删掉，别人举报他的<b>行留着</b>、只把 {@code target_user} 置空。
     *
     * <p>这不是数据洁癖的两种口味，而是两件事：前者是他名下的数据，账号走了它就该走；
     * 后者是别人递上来的工单，如果注销就能把自己被举报的历史一起洗掉，那"注销再同名重建"
     * 就成了违规昵称的洗白通道。所以这里必须是两个不同的调用，而不是一个 {@code DELETE ... WHERE uid}。
     */
    @Test
    void leavingTheReportsAboutHimIsNotTheSameAsDeletingThem() {
        UserMapper users = mock(UserMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        SaveMapper saves = mock(SaveMapper.class);
        AnalyticsMapper analytics = mock(AnalyticsMapper.class);
        AdTicketMapper tickets = mock(AdTicketMapper.class);
        ModerationMapper mod = mock(ModerationMapper.class);
        when(mod.unreportTarget(7L)).thenReturn(6);

        Map<String, Object> done = purge(users, sessions, saves, analytics, tickets, mod).purge(7L);

        verify(mod).deleteReportsBy(7L);
        verify(mod).unreportTarget(7L);
        assertEquals(6, done.get("reportsAbout"), "被置空的那几条要能在审计里数出来，否则'洗没洗掉'无从对账");
    }

    /** 用户行排最后：前面任何一张表抛了，账号还在，运营能重试而不是留下一个半删状态。 */
    @Test
    void theUserRowIsDeletedLastSoAPartialFailureLeavesSomethingToRetry() {
        UserMapper users = mock(UserMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        SaveMapper saves = mock(SaveMapper.class);
        AnalyticsMapper analytics = mock(AnalyticsMapper.class);
        AdTicketMapper tickets = mock(AdTicketMapper.class);
        ModerationMapper mod = mock(ModerationMapper.class);
        when(analytics.purgeUser(7L)).thenThrow(new RuntimeException("boom"));

        assertThrows(RuntimeException.class,
                () -> purge(users, sessions, saves, analytics, tickets, mod).purge(7L));
        verify(users, never()).delete(7L);
        verify(mod, never()).deleteReportsBy(7L);
    }

    /* ---------------- 4. 库删干净之后，进程内的现场也要跟着走（G6 + G7） ---------------- */

    /**
     * 删号不只删库：{@code GameService} 的挑战临时台/沙盒与 {@code CurfewGuard} 的 minor 判定
     * 都是按 uid 躺在内存里的格子，人走了它们还在。
     *
     * <p>断言的是<b>顺序</b>而不是"调过没调过"：清理必须排在所有 SQL 之后。反过来（先清内存再删库），
     * 一旦事务回滚就留下"号还在、现场没了"的怪相——玩家的临时台凭空重开，而账号看起来完全正常。
     */
    @Test
    void theMemorySideIsEvictedAfterTheDatabaseIsSwept() {
        UserMapper users = mock(UserMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        SaveMapper saves = mock(SaveMapper.class);
        AnalyticsMapper analytics = mock(AnalyticsMapper.class);
        AdTicketMapper tickets = mock(AdTicketMapper.class);
        ModerationMapper mod = mock(ModerationMapper.class);
        List<String> order = new ArrayList<>();
        when(users.delete(7L)).thenAnswer(i -> { order.add("user"); return 1; });
        // 现场清理方：记下"库里的最后一行都删完之后才轮到它"
        AccountMemoryEvictor memory = uid -> order.add("evict:" + uid);

        new AccountPurge(users, sessions, saves, analytics, tickets, mod, List.of(memory)).purge(7L);

        assertEquals(List.of("user", "evict:7"), order,
                "内存现场要在删库成功之后清，一次、只清这一位");
    }

    /**
     * 清理方抛错不许把删号事务带崩：库已经删干净了，内存那一格交给它自己的闲置清扫收尾（G7）。
     *
     * <p>这一条决定的是回滚方向：如果让内存清理的异常往上冒，{@code @Transactional} 会把整笔删除回滚，
     * 玩家注销一次却"删不掉"，而且越试越删不掉——比留下一格没人读的现场糟得多。
     */
    @Test
    void aThrowingEvictorNeitherFailsThePurgeNorBlocksTheOthers() {
        UserMapper users = mock(UserMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        SaveMapper saves = mock(SaveMapper.class);
        AnalyticsMapper analytics = mock(AnalyticsMapper.class);
        AdTicketMapper tickets = mock(AdTicketMapper.class);
        ModerationMapper mod = mock(ModerationMapper.class);
        AtomicLong reached = new AtomicLong(-1L);
        when(users.delete(7L)).thenReturn(1);
        AccountMemoryEvictor boom = uid -> {
            throw new IllegalStateException("内存那边抛了");
        };
        AccountMemoryEvictor ok = uid -> reached.set(uid);

        Map<String, Object> done = assertDoesNotThrow(() -> new AccountPurge(
                users, sessions, saves, analytics, tickets, mod, List.of(boom, ok)).purge(7L));

        assertEquals(7L, reached.get(), "一个清理方失败不连累其余的：该清的还得清");
        assertEquals(1, done.get("user"), "删号结果照常回报，不受内存那一侧影响");
    }

    /** 没装配清理方（老单测与 {@code AuthService} 的测试口径）照样跑得通：这一步是尽力而为，不是必要环节。 */
    @Test
    void purgeWorksWithoutAnyMemoryEvictor() {
        UserMapper users = mock(UserMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        SaveMapper saves = mock(SaveMapper.class);
        AnalyticsMapper analytics = mock(AnalyticsMapper.class);
        AdTicketMapper tickets = mock(AdTicketMapper.class);
        ModerationMapper mod = mock(ModerationMapper.class);

        assertDoesNotThrow(() -> new AccountPurge(
                users, sessions, saves, analytics, tickets, mod, List.of()).purge(7L));
        assertDoesNotThrow(() -> purge(users, sessions, saves, analytics, tickets, mod).purge(7L));
        // 两条装配路各删一次同一个 uid：空列表那种（Spring 没收到任何清理方）与老六参构造
        verify(users, times(2)).delete(7L);
    }
}
