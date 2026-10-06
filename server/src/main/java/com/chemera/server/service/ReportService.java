package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.mapper.ModerationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家举报（G2 的后端那一半）。
 *
 * <p>为什么非要有玩家侧入口：这游戏有昵称、有好友互访、有挂单，属于 UGC。后台【审核】里那张举报表
 * 从建库起就是 0 行——`ModerationMapper.addReport` 一直没有调用者，等于审核页那一栏是给空气做的。
 * 真出了违规内容，玩家只能去商店评论区或者邮件，而运营在后台看到的是一片绿。
 *
 * <p>三道闸门按"贵的那道先判"排：
 * <ol>
 *   <li><b>类型闭合</b>：{@code kind} 只认 {@link #KINDS} 里那几个。表里的 {@code kind} 列在 V1 写的是
 *       注释而不是枚举，注释会漂，所以闭合集合住在这里（Java 一份，和 {@code ConfigSpec} 同一个思路）。</li>
 *   <li><b>对象真实</b>：举报"人"必须给一个存在的 uid，且不能是自己；举报挂单必须给 ref。
 *       空转工单最坏——运营点开发现无从查证，就会开始不信这个列表。</li>
 *   <li><b>量</b>：同一 (reporter, kind, ref) 只留一条在途；每人每日 {@code chemera.report.daily-max} 条。
 *       加上控制器那两道 {@code RateGuard}（IP 与 uid 各一道）。</li>
 * </ol>
 *
 * <p><b>举报理由刻意不套敏感词闸门</b>：玩家引述的往往正是那句违规话，拦它等于把举报按钮交给被举报的人。
 * 需要拦的是"写进游戏里给所有人看"的文本（昵称那一路已经过 {@link com.chemera.server.game.SensitiveFilter}
 * 的分级判定），而这条通道是写给运营一个人看的。所以理由只做长度收口。
 */
@Service
public class ReportService {

    /** 闭合集合：V1 那行列注释只是注释，改这里不需要动库。 */
    public static final List<String> KINDS = List.of("nickname", "listing", "behavior", "other");

    private static final int REF_MAX = 64;
    private static final int REASON_MAX = 400;

    private final ModerationMapper mod;
    private final int dailyMax;

    public ReportService(ModerationMapper mod,
                         @Value("${chemera.report.daily-max:10}") int dailyMax) {
        this.mod = mod;
        this.dailyMax = Math.max(1, dailyMax);
    }

    /**
     * 收下这一条举报。返回给客户端的是"运营会看到什么"，不是内部 id——
     * 让玩家拿不到自增主键，也就拿不到"我的举报被别人也看得到"这种横向信息。
     */
    public Map<String, Object> file(long reporter, String kind, Long target, String ref, String reason) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        if (!KINDS.contains(k)) throw new BizException("没有这一类举报");

        String r = ref == null ? "" : ref.trim();
        if (r.length() > REF_MAX) throw new BizException("举报对象标识太长了");
        String why = reason == null ? "" : reason.trim();
        if (why.length() > REASON_MAX) throw new BizException("举报说明最多 " + REASON_MAX + " 个字");

        Long t = target;
        if ("nickname".equals(k) || "behavior".equals(k)) {
            if (t == null || t <= 0) throw new BizException("请指明要举报哪位玩家");
            if (t == reporter) throw new BizException("不能举报自己");
            if (mod.userExists(t) == 0) throw new BizException("查不到这位玩家");
        } else if ("listing".equals(k)) {
            if (r.isBlank()) throw new BizException("请指明是哪一张挂单");
        } else if (why.isBlank()) {
            throw new BizException("请写一句发生了什么");
        }
        if ("other".equals(k) && r.isBlank() && t == null) {
            // 什么对象都没有的一条"其他"，运营只能当垃圾看；不给它开这条口子
            throw new BizException("请指明举报对象");
        }

        if (mod.openDuplicate(reporter, k, r) > 0)
            throw new BizException("这条已经在处理了，谢谢");
        if (mod.countToday(reporter) >= dailyMax)
            throw new BizException("今天提交的举报已经够多了，明天再看有没有需要补充的");

        mod.addReport(reporter, t, k, r.isBlank() ? null : r, why.isBlank() ? null : why);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", k);
        out.put("openToday", mod.countToday(reporter));
        out.put("dailyMax", dailyMax);
        return out;
    }
}
