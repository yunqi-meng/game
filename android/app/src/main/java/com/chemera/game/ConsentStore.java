package com.chemera.game;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 隐私同意状态的唯一存放处（应用私有 SharedPreferences）。
 *
 * <p>为什么要单独一个类、还要持久化：审核规范（TapTap 5.8 + 个保法）要的是"同意之前不初始化任何
 * 第三方 SDK"，而"同意"这件事必须满足两条才好查证——一是重启后依然记得（否则每次冷启都要重新问，
 * 玩家会乱），二是原生侧自己能判（不能只信 WebView 里传进来的标志：那是个可以在控制台改的变量）。
 * 所以判定权留在原生：{@code ChemeraAd.showRewardVideo} 自己读这里，读不到同意就直接拒。
 *
 * <p>{@code askedAt}/{@code revokedAt} 记下同意与撤回的时间，供合规导出与排查"到底哪个包带了同意"用；
 * 它们不参与任何逻辑判断，删了也不影响功能。
 */
final class ConsentStore {

    private static final String FILE = "chemera-consent";
    private static final String KEY_AGREED = "privacy_agreed";
    private static final String KEY_ASKED_AT = "privacy_agreed_at";
    private static final String KEY_REVOKED_AT = "privacy_revoked_at";

    private ConsentStore() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean agreed(Context c) {
        return sp(c).getBoolean(KEY_AGREED, false);
    }

    /** 同意：由登录页那个"我已阅读并同意"的显式动作触发，不接受任何隐式默认。 */
    static void agree(Context c) {
        sp(c).edit()
            .putBoolean(KEY_AGREED, true)
            .putLong(KEY_ASKED_AT, System.currentTimeMillis())
            .apply();
    }

    /**
     * 撤回同意。个保法第十五条给玩家的就是这条路，所以原生必须提供它——
     * "只允许同意方向落盘、撤回靠清数据"那种写法在提审时会被直接问住。
     *
     * <p>撤回之后：不再初始化、不再调起任何第三方 SDK（判定都在 agreed() 这一处）；
     * 已经起来的 SDK 进程内没法干净地卸掉，所以这里同时把时间记下来，
     * 真接入 SDK 后若它提供 stop()/release()，就在这个方法里补一行调用。
     */
    static void revoke(Context c) {
        sp(c).edit()
            .putBoolean(KEY_AGREED, false)
            .putLong(KEY_REVOKED_AT, System.currentTimeMillis())
            .apply();
    }

    static long agreedAt(Context c) {
        return sp(c).getLong(KEY_ASKED_AT, 0L);
    }

    static long revokedAt(Context c) {
        return sp(c).getLong(KEY_REVOKED_AT, 0L);
    }
}
