package com.tapsdk;

/**
 * 探针类：只给 <code>test/android-check.sh</code> 的③号断言用，真实 SDK 里不会有这个名字。
 *
 * <p>为什么需要它：本模块的桩与真实 TapSDK <b>同名同类</b>（不这样 javac 就认不下来），
 * 于是"dex 里出现 com/tapsdk/tapad"这个信号一度分不清"平台给的 AAR 进包了"和"桩不小心被打进包了"。
 * app 侧因此把依赖改成 compileOnly（桩只活到编译期），而这条约定需要有人守：
 * 谁哪天把它改回 implementation，包里没有真 SDK 却会被自检判成"可投放激励视频"——
 * 那正是提审前最需要信得过的一个结论。有这个独一份的类名在，dex 一扫就知道桩在不在包里。
 *
 * <p>它不被任何代码引用。目前两个 buildType 都没开代码压缩（minifyEnabled false），所以它一定在包里；
 * 将来谁开了压缩，这个类会被剥离、探针就扫不到了——那时请改判据（例如读构建日志里的 AD_SDK_BUNDLED），
 * 别把断言悄悄改成"扫不到就算通过"。
 */
public final class ChemeraCompileOnlyStub {

    private ChemeraCompileOnlyStub() {}
}
