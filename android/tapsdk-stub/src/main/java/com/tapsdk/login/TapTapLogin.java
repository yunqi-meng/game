package com.tapsdk.login;

import android.app.Activity;
import com.tapsdk.tds.ServiceResultCallback;
import com.tapsdk.tds.TDSError;

/** 编译期桩（非真实 SDK）。 */
public final class TapTapLogin {
    private TapTapLogin() {}

    public static boolean isLogin() { return false; }

    public static void login(Activity activity, ServiceResultCallback<TapTapLoginResult, TDSError> callback) {
        throw new IllegalStateException("TapSDK 未内置：这个包用的是编译期桩");
    }
}
