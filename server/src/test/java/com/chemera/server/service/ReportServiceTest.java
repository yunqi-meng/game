package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.mapper.ModerationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 举报受理的三道闸（G2）。
 *
 * <p>后台那张 {@code report} 表从建库起是 0 行——{@code addReport} 一直没有调用者。现在有了玩家侧入口，
 * 真正的风险不是"收不到"，而是<b>收进来一堆没法查证的工单</b>：运营点开发现无从下手，
 * 就会开始不信这个列表，于是整个审核面又变回空气。所以下面这几条钉的全是"什么样的东西不许进来"。
 *
 * <p>另一件必须钉住的事：举报理由<b>不套敏感词闸门</b>。玩家引述的往往正是那句违规话，
 * 拦它等于把举报按钮交给被举报的人。这条在代码里没有依赖可断言，所以用"带脏字的理由照样落库"来钉。
 */
class ReportServiceTest {

    private static ModerationMapper open(long dup, long today, long exists) {
        ModerationMapper m = mock(ModerationMapper.class);
        when(m.openDuplicate(anyLong(), anyString(), anyString())).thenReturn(dup);
        when(m.countToday(anyLong())).thenReturn(today);
        when(m.userExists(anyLong())).thenReturn(exists);
        return m;
    }

    private static ReportService svc(ModerationMapper m, int dailyMax) {
        return new ReportService(m, dailyMax);
    }

    /* ---------------- 1. 类型闭合 ---------------- */

    @Test
    void anUnknownKindIsRefusedOutright() {
        ModerationMapper m = open(0, 0, 1);
        BizException e = assertThrows(BizException.class, () -> svc(m, 10).file(7, "harassment", 8L, "", "骂人"));
        assertTrue(e.getMessage().contains("没有这一类举报"));
        verify(m, never()).addReport(any(), any(), anyString(), any(), any());
    }

    /** 大小写与空格由这里收口，别让同一件事在表里存成 nickname / NickName 两副面孔。 */
    @Test
    void kindIsNormalisedBeforeItIsStored() {
        ModerationMapper m = open(0, 0, 1);
        svc(m, 10).file(7, "  NickName ", 8L, "  ", "  改了个侮辱性名字  ");
        ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
        verify(m).addReport(eq(7L), eq(8L), kind.capture(), isNull(), anyString());
        assertEquals("nickname", kind.getValue());
    }

    /* ---------------- 2. 对象真实 ---------------- */

    @Test
    void reportingAPersonRequiresAPersonThatExists() {
        ModerationMapper nobody = open(0, 0, 0);
        assertThrows(BizException.class, () -> svc(nobody, 10).file(7, "behavior", 99L, "", "骂人"));
        verify(nobody, never()).addReport(any(), any(), anyString(), any(), any());

        ModerationMapper m = open(0, 0, 1);
        assertThrows(BizException.class, () -> svc(m, 10).file(7, "behavior", null, "", "骂人"),
                "没有对象的'行为举报'没法查证");
    }

    /** 自举报不是"没意义"这么简单：它能占住那条在途限额，把自己的号挡在举报之外。 */
    @Test
    void youCannotReportYourself() {
        ModerationMapper m = open(0, 0, 1);
        BizException e = assertThrows(BizException.class, () -> svc(m, 10).file(7, "nickname", 7L, "", "怪名字"));
        assertTrue(e.getMessage().contains("不能举报自己"));
    }

    @Test
    void aListingReportNeedsToSayWhichListing() {
        ModerationMapper m = open(0, 0, 1);
        assertThrows(BizException.class, () -> svc(m, 10).file(7, "listing", null, "", "假挂单"));
        svc(m, 10).file(7, "listing", null, "L-2026-1006-3", "假挂单");     // 有 ref 就收
        verify(m).addReport(eq(7L), isNull(), eq("listing"), eq("L-2026-1006-3"), anyString());
    }

    /** "其他"这一类最容易变成情绪垃圾桶：什么对象都没有的一条，运营只能当垃圾看。 */
    @Test
    void anOtherReportStillNeedsAnObjectAndAReason() {
        ModerationMapper m = open(0, 0, 1);
        assertThrows(BizException.class, () -> svc(m, 10).file(7, "other", null, "", "感觉不对"));
        assertThrows(BizException.class, () -> svc(m, 10).file(7, "other", null, "ref-1", "   "),
                "这一类要写清发生了什么，否则工单没有内容可判");
        assertDoesNotThrow(() -> svc(m, 10).file(7, "other", 8L, "", "在游戏里骚扰别人"));
    }

    @Test
    void overLongFieldsAreRefusedRatherThanTruncated() {
        ModerationMapper m = open(0, 0, 1);
        assertThrows(BizException.class, () -> svc(m, 10).file(7, "other", 8L, "x".repeat(65), "说明"));
        assertThrows(BizException.class, () -> svc(m, 10).file(7, "other", 8L, "", "说".repeat(401)));
        verify(m, never()).addReport(any(), any(), anyString(), any(), any());
    }

    /* ---------------- 3. 量：在途一条、每日上限 ---------------- */

    @Test
    void theSameOpenCaseIsNotFiledTwice() {
        ModerationMapper m = open(1, 0, 1);
        BizException e = assertThrows(BizException.class, () -> svc(m, 10).file(7, "nickname", 8L, "", "怪名字"));
        assertTrue(e.getMessage().contains("已经在处理"), "重复举报要给一句'已经在处理'，而不是静默收下再存一行：" + e.getMessage());
        verify(m, never()).addReport(any(), any(), anyString(), any(), any());
    }

    @Test
    void theDailyCapIsEnforcedAndReportedBack() {
        ModerationMapper m = open(0, 3, 1);
        assertThrows(BizException.class, () -> svc(m, 3).file(7, "nickname", 8L, "", "又一个"));
        verify(m, never()).addReport(any(), any(), anyString(), any(), any());
    }

    /**
     * 理由里带敏感词也照收。
     *
     * <p>这条是"故意不做"的钉子：举报文本是给运营一个人看的，不是发给全服看的。
     * 谁哪天顺手在 {@code ReportService} 里加一道 {@code SensitiveFilter.block}，
     * 被举报人只要让自己的违规昵称命中拦截档，就能让所有针对他的举报都发不出来。
     */
    @Test
    void aReasonQuotingTheBadWordsIsStillFiled() {
        ModerationMapper m = open(0, 0, 1);
        Map<String, Object> r = svc(m, 10).file(7, "nickname", 8L, "", "他把昵称改成了那句最难听的脏话");
        assertEquals("nickname", r.get("kind"));
        verify(m).addReport(eq(7L), eq(8L), eq("nickname"), isNull(), contains("脏话"));
    }

    /** 回执只给"今天还能提几条"，不给自增主键：横向信息不该发给玩家。 */
    @Test
    void theReceiptCarriesQuotaNotInternalIds() {
        ModerationMapper m = open(0, 1, 1);
        when(m.countToday(7L)).thenReturn(1L, 2L);
        Map<String, Object> r = svc(m, 5).file(7, "behavior", 8L, "", "骂人");
        assertEquals("behavior", r.get("kind"));
        assertEquals(2L, r.get("openToday"), "落库之后再数一次，报的是真实条数");
        assertEquals(5, r.get("dailyMax"));
        assertFalse(r.toString().contains("id="), "回执里不该出现内部 id：" + r);
    }
}
