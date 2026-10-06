package com.tapsdk.launcher;

import android.app.Activity;
import com.tapsdk.tds.TDSConfig;

/** 编译期桩（非真实 SDK）。 */
public final class TapBootstrap {
    private TapBootstrap() {}

    public static void init(Activity activity, TDSConfig config) {
        throw new IllegalStateException("TapSDK 未内置：这个包用的是编译期桩，请向平台索取 TapBootstrapper/TapLogin 的 AAR 放入 app/libs/");
    }
}
