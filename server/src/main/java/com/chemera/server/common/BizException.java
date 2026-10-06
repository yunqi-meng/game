package com.chemera.server.common;

/** 业务异常：携带对外可读信息，由全局异常处理器转为 ApiResponse。 */
public class BizException extends RuntimeException {
    public final int code;
    /**
     * 机器可读的错误标签（可选）。文案是给人看的，会被改字、翻译、调语序，
     * 客户端要按分支处理的东西不能靠 substring 匹配 msg——那正是 CURFEW 这类闸门需要的。
     */
    public String tag;

    public BizException(String msg) { this(400, msg); }
    public BizException(int code, String msg) { super(msg); this.code = code; }

    /** 链式打标签：只在需要客户端分流的少数地方用，别拿它当第二套错误码体系。 */
    public BizException tagged(String t) { this.tag = t; return this; }

    public static BizException notFound(String what) { return new BizException(404, what + "不存在"); }
    public static BizException forbidden(String msg) { return new BizException(403, msg); }
    public static BizException unauthorized(String msg) { return new BizException(401, msg); }
    public static BizException tooMany(String msg) { return new BizException(429, msg); }

    /**
     * 乐观锁冲突（H6-2）：这一行在你打开对话框之后被别人动过（或被删了、或已存在）。
     *
     * <p>用 <b>409</b> 而不是这仓库里业务错误的默认形状（200 + {@code ok:false}）：面板要据此分流
     * ——冲突不是"请求写错了"，而是"你手上那份过期了"，界面得给出「载入最新再改」这条出路，
     * 而不是把服务端原话当一行红字飘过去。状态码是这条分流唯一可靠的依据，文案随时会改字。
     */
    public static BizException conflict(String msg) {
        return new BizException(409, msg).tagged("CONFLICT");
    }

    /**
     * "这一行不是你打开对话框时那一行了"（H6-2 的乐观锁冲突）。
     *
     * <p>把 {@code at}/{@code by} 带上是因为运营真正要回答的问题是"我这份草稿还要不要"：
     * 只说"保存失败"的话，他下一次动作必然是再点一次保存，而那就正是要拦的那次覆盖。
     * {@code at == null} 表示这一行已经被删了——出路不同（不是重载最新内容，是确认该不该重建）。
     */
    public static BizException staleWrite(String what, java.time.LocalDateTime at, String by) {
        if (at == null)
            return conflict(what + "在你编辑期间已被删除，这次写入已取消。请刷新列表确认它还要不要重建——"
                    + "直接新建会连别人后续的改动一起丢掉。");
        return conflict(what + "在你编辑期间已被" + (by == null || by.isBlank() ? "他人" : "「" + by + "」")
                + "于 " + show(at) + " 改动，这次写入已取消。请载入最新版本再改（你手上那份不会落库），"
                + "直接覆盖会把那一次改动抹掉。");
    }

    /** 给人看的时间：{@code 2026-09-24 20:23:33}。版本号仍是 ISO 原文，两者别混用。 */
    private static String show(java.time.LocalDateTime at) {
        return at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 面板按"这行还不存在"提交新增，实际却撞上已有行。 */
    public static BizException createdAfter(String what, java.time.LocalDateTime at, String by) {
        if (at == null)   // 撞上主键却又读不到行：只可能是那一瞬间又被删了，说清楚别编一个"于 null 建出来"
            return conflict(what + "在你填表期间已经被建出来又删掉了，这次新增已取消。请刷新列表确认它的当前状态。");
        return conflict(what + "在你填表期间已被" + (by == null || by.isBlank() ? "他人" : "「" + by + "」")
                + "于 " + show(at) + " 建出来，这次新增已取消。请刷新列表后编辑那一行，"
                + "新建同名项会直接盖掉对方那份。");
    }
}
