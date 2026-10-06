package com.chemera.game;

import android.app.Activity;

/**
 * TapTap 登录能力的宿主侧接口，和 {@link AdProvider} 同一套取舍：TapSDK 是本地 AAR，
 * 没放进来就没有实现，壳就老实回答"未内置"，而不是摆一个必然失败的入口。
 *
 * <p>这里刻意只负责"要到一张票据"。票据不是身份：客户端拿到的东西服务端一律要去
 * open.tapapis.cn 验签才算数，所以这条链路允许壳随便传，风险由服务端兜（见 AuthService.taptap）。
 */
public interface LoginProvider {

    interface Callback {
        /** ticket 为可 JSON 序列化的票据字符串；失败时 code 给人话原因。 */
        void onResult(String ticket, String code, String msg);
    }

    boolean isReady();

    void init(Activity activity, String taptapAppId);

    /** 唤起 TapTap 授权；已登录时直接回票据，不弹界面。 */
    void requestTicket(Activity activity, Callback callback);
}
