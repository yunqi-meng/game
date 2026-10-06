package com.chemera.game;

import android.util.Log;

/**
 * "这个包里到底有没有带第三方 SDK"的唯一判定处。
 *
 * <p>判定先看 {@code BuildConfig.AD_SDK_BUNDLED}（构建时 app/libs 下有没有 AAR，见 app/build.gradle），
 * 再去 Class.forName 找实现类。两步都要，是因为它们各自挡一种事故：
 * <ul>
 *   <li><b>没带 AAR</b>：provider 类现在**总是**会被编进包里（没有 AAR 时它对着 tapsdk-stub 那份编译期桩
 *       编译，好让 SDK 签名对不上时在构建期就报错），所以单靠"类在不在"已经判不出包没带 SDK 了——
 *       必须由这个布尔量先拦一道，否则会去实例化一个方法体会抛"未内置"的桩；</li>
 *   <li><b>带了 AAR 但类和编译时不一致</b>：有人手工往 libs 塞了改名/裁剪过的包，provider 能装进来、
 *       却解析不到它依赖的 SDK 类。这是 {@code NoClassDefFoundError}（{@code LinkageError} 的一种），
 *       必须吵出来，否则玩家只会看到"广告不可用"。</li>
 * </ul>
 */
final class Providers {

    private static final String TAG = "chemera";
    private static final String AD_IMPL = "com.chemera.game.ad.TapAdnProvider";
    private static final String LOGIN_IMPL = "com.chemera.game.ad.TapTapLoginProvider";

    private static AdProvider ad;
    private static LoginProvider login;
    private static boolean adTried;
    private static boolean loginTried;

    private Providers() {}

    static synchronized AdProvider ad() {
        if (adTried) return ad;
        adTried = true;
        if (!BuildConfig.AD_SDK_BUNDLED) {
            Log.i(TAG, "这个安装包没有内置广告 SDK（app/libs 下无 AAR），看广告会明确报不可用");
            return ad;
        }
        try {
            ad = (AdProvider) Class.forName(AD_IMPL).getDeclaredConstructor().newInstance();
            Log.i(TAG, "广告 SDK 实现已装载：" + AD_IMPL);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "带了 AAR 却找不到广告 provider 实现，检查 app/src/tapadn 是否被排除", e);
        } catch (LinkageError | ReflectiveOperationException e) {
            Log.e(TAG, "广告 provider 装载失败：AAR 与编译时的类不一致，需要重新按文档放包", e);
        }
        return ad;
    }

    static synchronized LoginProvider login() {
        if (loginTried) return login;
        loginTried = true;
        if (!BuildConfig.AD_SDK_BUNDLED) {
            // TapADN 与 TapSDK 是两份独立的 AAR，但当前构建口径是"要么都带要么都不带"
            //（app/build.gradle 的 adAar 只看 libs 目录）。真要拆开发布，就再拆一个 BuildConfig 布尔量，
            // 别在这里加"猜"的逻辑。
            Log.i(TAG, "这个安装包没有内置 TapSDK，登录页会隐藏 TapTap 入口");
            return login;
        }
        try {
            login = (LoginProvider) Class.forName(LOGIN_IMPL).getDeclaredConstructor().newInstance();
            Log.i(TAG, "TapTap 登录实现已装载：" + LOGIN_IMPL);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "带了 TapSDK 却找不到登录 provider 实现，检查 app/src/tapadn 是否被排除", e);
        } catch (LinkageError | ReflectiveOperationException e) {
            Log.e(TAG, "TapTap provider 装载失败：TapSDK 与编译时的类不一致", e);
        }
        return login;
    }
}
