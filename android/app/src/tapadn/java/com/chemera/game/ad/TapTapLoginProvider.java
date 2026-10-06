package com.chemera.game.ad;

import android.app.Activity;
import android.util.Log;

import com.chemera.game.LoginProvider;
import com.getcapacitor.JSObject;
import com.tapsdk.launcher.TapBootstrap;
import com.tapsdk.login.TapTapLogin;
import com.tapsdk.login.TapTapLoginResult;
import com.tapsdk.tds.TDSError;
import com.tapsdk.tds.TDSConfig;
import com.tapsdk.tds.ServiceResultCallback;

/**
 * TapTap 登录票据实现，和 {@link TapAdnProvider} 一样**总是参与编译**（没有本地 AAR 时
 * 对着 android/tapsdk-stub 那份同名编译期桩编）。运行期只有带 TapSDK 的包才会装载它，
 * 判定在 {@code Providers.login()}：先看 BuildConfig，再找类。
 *
 * <p><b>这个文件是接入时唯一要核对的地方。</b>TapSDK 用本地 AAR 分发，类名与包路径随版本变
 * （3.x 的 com.tapsdk.login.*，个别版本把 TDSConfig 挪进 com.tapsdk.tds 之外）。放进来 AAR
 * 若签名对不上，编译会立刻在这里报错——这比运行期"点了没反应"好太多：报错的位置就是改的地方，
 * 而且改完不用回归游戏逻辑，因为票据的采信全在服务端。
 *
 * <p>要交出去的东西只有一个：一张能拿去 open.tapapis.cn 验签的票据。它不是身份，
 * 所以这里不需要"猜"用户是谁，也不需要缓存登录态（{@code TapTapLogin.isLogin()} 只用来少走一次授权）。
 */
public final class TapTapLoginProvider implements LoginProvider {

    private static final String TAG = "chemera-login";

    private volatile boolean inited;
    /** client_id 要随票据一起交上去：服务端 TapTapVerifier 允许客户端不带，但它带了自己那份就必须是自洽的。 */
    private volatile String clientId = "";

    @Override
    public boolean isReady() {
        return inited;
    }

    @Override
    public void init(Activity activity, String taptapAppId) {
        if (inited || taptapAppId == null || taptapAppId.isEmpty()) return;
        // 核对点 1：不同 TapSDK 版本里 TDSConfig 的构造方式不同（有的读 assets/taptap-config.json，
        // 有的用 builder）。若这里是编译错误，按手上那版 SDK 的 quickstart 改这三行即可。
        TDSConfig config = new TDSConfig();
        config.initClientId(taptapAppId, "", "");
        // 与广告侧同一条底线：SDK 意外只让"这次登录没成"，玩家还能用账号密码或游客进。
        // inited 只在真的起来之后才置位，否则 requestTicket 会拿着半初始化状态去调 login。
        try {
            TapBootstrap.init(activity, config);
            clientId = taptapAppId;
            inited = true;
            Log.i(TAG, "TapSDK 初始化完成（clientId 长度=" + taptapAppId.length() + "）");
        } catch (Throwable t) {
            Log.e(TAG, "TapSDK 初始化失败：本次会话没有 TapTap 登录入口", t);
        }
    }

    @Override
    public void requestTicket(final Activity activity, final Callback callback) {
        if (!inited) {
            callback.onResult(null, "NO_INIT", "TapSDK 未初始化（构建时未注入 CHEMERA_TAPTAP_CLIENT_ID）");
            return;
        }
        try {
            // 核对点 2：登录方法名与结果对象的取值路径随 TapSDK 版本变。
            // 但**交出去的字段名不能改**：服务端 TapTapVerifier.verify 认的是 {kid, macKey, clientId}，
            // 它拿这三个值去 open.tapapis.cn 做 MAC 验签（见 server/.../TapTapVerifier.java）。
            // 所以这个方法的真正职责是"把 SDK 结果里的 kid/macKey 映射成服务端要的键名"，
            // 而不是"把 SDK 的对象原样丢过去"。
            TapTapLogin.login(activity, new ServiceResultCallback<TapTapLoginResult, TDSError>() {
                @Override
                public void onResult(TapTapLoginResult result) {
                    if (result == null || result.getTapLoginResult() == null) {
                        callback.onResult(null, "EMPTY", "TapTap 授权未完成");
                        return;
                    }
                    // 核对点 3：getKid()/getMacKey() 的取法。若这版 SDK 只给 access_token，
                    // 就改用它的票据接口取 kid/macKey；拿不到就不要伪造——服务端一定会验不过。
                    String kid = String.valueOf(result.getTapLoginResult().getKid());
                    String macKey = String.valueOf(result.getTapLoginResult().getMacKey());
                    if (isEmptyish(kid) || isEmptyish(macKey)) {
                        callback.onResult(null, "EMPTY", "未取到 TapTap 登录票据（kid/macKey 为空）");
                        return;
                    }
                    JSObject ret = new JSObject();
                    ret.put("kid", kid);
                    ret.put("macKey", macKey);
                    // 交出去的是 init 时记下的那一份 clientId（字段名就叫 clientId，别写成 appId：
                    // 这个文件现在虽然总是参与编译，但当年这里写的 appId 是个不存在的变量，
                    // 只是那时它没被 javac 编过，所以错误一直没现形）。
                    ret.put("clientId", clientId);
                    callback.onResult(ret.toString(), null, null);
                }

                @Override
                public void onError(TDSError error) {
                    String msg = error == null || error.getMessage() == null ? "TapTap 授权失败" : error.getMessage();
                    callback.onResult(null, "TAPSDK_ERROR", msg);
                }
            });
        } catch (Throwable t) {
            // SDK 内部在任何设备上都不该把宿主打崩；这里兜住的后果只是"这次登录没成"，
            // 玩家还能用账号密码或游客进入，进度不会丢。
            Log.e(TAG, "调用 TapSDK 登录异常", t);
            callback.onResult(null, "TAPSDK_THROW", "TapTap 授权异常：可先用账号密码或游客进入");
        }
    }

    private static boolean isEmptyish(String s) {
        return s == null || s.isEmpty() || "null".equals(s);
    }
}
