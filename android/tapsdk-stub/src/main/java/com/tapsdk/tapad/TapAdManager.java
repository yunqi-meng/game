package com.tapsdk.tapad;

import android.app.Activity;

/** 编译期桩（非真实 SDK）。 */
public final class TapAdManager {
    private static final TapAdManager ONE = new TapAdManager();
    private TapAdManager() {}

    public static TapAdManager get() { return ONE; }

    public TapAdNative createAdNative(Activity activity) {
        throw new IllegalStateException("TapADN SDK 未内置：这个包用的是编译期桩");
    }
}
