package com.chemera.server.common;

import java.time.LocalDateTime;

/**
 * 一次写入对"当前那一行长什么样"的前提（H6-2 乐观锁）。
 *
 * <p>为什么拿 {@code updated_at} 当版本号而不是新加一列：{@code V1__baseline} 给
 * {@code content_item} / {@code app_config} 都建了 {@code DATETIME(3) ON UPDATE CURRENT_TIMESTAMP(3)}，
 * 毫秒精度、由库盖章（两笔并发写谁先落库以库的顺序为准，不受应用节点时钟影响），
 * 而且后台列表本来就在回传它——加一列反而多一个"两处版本不一致"的新问题。
 *
 * <p>三种条件对应面板的三种状态：
 * <ul>
 *   <li>{@link Kind#FORCE}——请求没带 {@code expect}。覆盖写，留给不带版本号的批量写入
 *       （回归里的 {@code hput}/{@code putcfg}、将来的回填脚本：一次推几百行，拿不到也不需要逐行版本号）。</li>
 *   <li>{@link Kind#NEW}——{@code expect=none}，"我看到的列表里没有这一行"。撞上已有键就是别人抢先建了，
 *       绝不能退化成 upsert 把人家的盖掉。</li>
 *   <li>{@link Kind#UNCHANGED}——带回了 {@code updatedAt} 原文，这一行必须还是那一版。</li>
 * </ul>
 *
 * <p>缺省走 FORCE 是刻意的兼容选择：真要强制每个调用方都带版本号，就得同时改掉那一批本来没有
 * "上一版"可回传的写入（回归脚本与将来的批量回填），而面板那条路径已经由 {@code Expect} 覆盖；
 * 后台是唯一的人写入口，脚本是已知的单操作者。
 */
public record Expect(Kind kind, LocalDateTime token) {

    public enum Kind { FORCE, NEW, UNCHANGED }

    /** {@code expect} 取这个值时表示"这一行还不该存在"。 */
    public static final String NONE = "none";

    /** 无条件覆盖。 */
    public static final Expect FORCE = new Expect(Kind.FORCE, null);

    /** 必须不存在。 */
    public static final Expect NEW = new Expect(Kind.NEW, null);

    /**
     * 把请求参数翻译成条件。面板带回来的就是列表里那份 {@code updatedAt} 原文，所以这里只解析不比较。
     *
     * <p>解析不了回 400 而不是 409：那是请求本身写坏了（手拼 URL、或面板把字段拼错），
     * 和"这一行被人改过"是两件事；混成一个码，冲突弹窗里就会出现一句答不上来的话。
     */
    public static Expect parse(String raw) {
        if (raw == null || raw.isBlank()) return FORCE;
        if (NONE.equals(raw.trim())) return NEW;
        try {
            return new Expect(Kind.UNCHANGED, LocalDateTime.parse(raw.trim()));
        } catch (Exception e) {
            throw new BizException("expect 得是列表里那份 updatedAt 原文（形如 2026-01-02T03:04:05.123）或 "
                    + NONE + "，现在是「" + raw + "」");
        }
    }

    public boolean isForce() { return kind == Kind.FORCE; }

    public boolean isNew() { return kind == Kind.NEW; }

    /** 条件更新/删除要用的时间戳；仅 {@link Kind#UNCHANGED} 有值。 */
    public LocalDateTime requireToken() {
        if (token == null) throw new BizException("内部错误：这一种写入条件不需要时间戳，调用方不该取它");
        return token;
    }
}
