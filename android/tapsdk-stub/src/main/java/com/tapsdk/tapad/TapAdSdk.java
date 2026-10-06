package com.tapsdk.tapad;

import android.app.Application;

/** 编译期桩（非真实 SDK）。 */
public final class TapAdSdk {
    private TapAdSdk() {}

    public static void init(Application app, TapAdConfig config) {
        throw new IllegalStateException("TapADN SDK 未内置：这个包用的是编译期桩，请向平台索取 AAR 放入 app/libs/");
    }
}
