package com.chemera.game;

import android.app.Application;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 激励视频的壳侧入口，前端唯一调用方是 {@code frontend/js/ads.js}。
 *
 * <p>三件事在这里定死，不给 JS 商量余地：
 * <ol>
 *   <li><b>没同意隐私政策就不初始化 SDK</b>（审核 5.8）——同意状态读原生
 *       {@link ConsentStore}，不读 JS 传来的标志；</li>
 *   <li><b>本方法不产生奖励</b>——它只负责"播一次广告"。奖励由服务端收到广告平台的 SSV 回调后结算，
 *       客户端这边连"播完了"都不算凭据，所以哪怕有人把这个文件改成永远回 finished=true
 *       也刷不出钻石（这一条和方案里"全服务端权威"是同一件事）；</li>
 *   <li><b>缺东西就明确拒绝</b>——没带 SDK、没配密钥、没同意，三种情况分别给不同 code，
 *       界面据此说人话，而不是笼统一句"广告加载失败"。</li>
 * </ol>
 *
 * <p>{@code call.setKeepAlive(true)}：Capacitor 默认一次调用只允许结算一次，而广告是
 * "加载 → 展示 → 关闭"三段异步，不保活的话第一个回调之后 call 就废了。
 */
@CapacitorPlugin(name = "ChemeraAd")
public class ChemeraAdPlugin extends Plugin {

    @PluginMethod
    public void status(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("bundled", BuildConfig.AD_SDK_BUNDLED);
        ret.put("hasProvider", Providers.ad() != null);
        ret.put("ready", Providers.ad() != null && Providers.ad().isReady());
        ret.put("consented", ConsentStore.agreed(getContext()));
        ret.put("agreedAt", ConsentStore.agreedAt(getContext()));
        ret.put("revokedAt", ConsentStore.revokedAt(getContext()));
        ret.put("mediaConfigured", !BuildConfig.AD_MEDIA_KEY.isEmpty());
        ret.put("versionCode", BuildConfig.VERSION_CODE);
        ret.put("versionName", BuildConfig.VERSION_NAME);
        call.resolve(ret);
    }

    /**
     * 记录"已同意/已撤回隐私政策"。同意之后才允许初始化任何第三方 SDK，所以这一步顺带触发 init；
     * 撤回则相反：把标志清掉并记下时间，此后 initIfNeeded/showRewardVideo 一律拒绝。
     *
     * <p>为什么这里允许 JS 写 false：撤回是玩家的法定权利（个保法第十五条），游戏内必须给入口
     * （【设置-隐私】那条），而"只能同意、撤回靠清数据"在提审时会被问住。
     * 同意方向仍然只有这一个显式方法能写——不存在任何默认值或隐式路径。
     */
    @PluginMethod
    public void consent(PluginCall call) {
        boolean agree = call.getBoolean("agree", false);
        if (!agree) {
            ConsentStore.revoke(getContext());
            JSObject off = new JSObject();
            off.put("agreed", false);
            off.put("revoked", true);
            off.put("ready", false);
            call.resolve(off);
            return;
        }
        ConsentStore.agree(getContext());
        initIfNeeded();
        JSObject ret = new JSObject();
        ret.put("agreed", true);
        ret.put("ready", Providers.ad() != null && Providers.ad().isReady());
        call.resolve(ret);
    }

    @Override
    public void load() {
        // 玩家早就同意过（第二次进游戏）：这时可以安静地把 SDK 起来，省掉首次观看时那一下等待。
        // 没同意过则什么都不做——这行判断本身就是合规要求的落地形态。
        initIfNeeded();
    }

    /** init 是幂等的：provider 内部自己判重，这里只保证"同意 + 有密钥 + 有实现"三个前提。 */
    private void initIfNeeded() {
        AdProvider p = Providers.ad();
        if (p == null || p.isReady()) return;
        if (!ConsentStore.agreed(getContext())) return;
        if (BuildConfig.AD_MEDIA_KEY.isEmpty()) return;
        p.init(app(), BuildConfig.AD_MEDIA_ID,
            BuildConfig.AD_MEDIA_NAME, BuildConfig.AD_MEDIA_KEY);
    }

    /**
     * SDK 的入口签名要的是 {@code Application}，而插件只保证拿到 {@code Context}，
     * 所以这里集中做一次转换：Capacitor 的 bridge context 就是应用上下文，转 Application 是安全的。
     */
    private Application app() {
        return (Application) getContext().getApplicationContext();
    }

    @PluginMethod
    public void showRewardVideo(PluginCall call) {
        call.setKeepAlive(true);
        AdProvider p = Providers.ad();
        if (p == null) {
            call.setKeepAlive(false);
            call.reject("这个安装包未内置广告 SDK，无法播放激励视频", "NO_SDK");
            return;
        }
        if (!ConsentStore.agreed(getContext())) {
            call.setKeepAlive(false);
            call.reject("请先同意隐私政策再观看广告", "NO_CONSENT");
            return;
        }
        if (BuildConfig.AD_MEDIA_KEY.isEmpty()) {
            call.setKeepAlive(false);
            call.reject("广告位尚未配置（构建时未注入 mediaKey）", "NO_MEDIA");
            return;
        }

        final String spaceId = orDefault(call.getString("spaceId"), BuildConfig.AD_SPACE_ID);
        final String userId = orDefault(call.getString("userId"), "");
        // extra 与 transId 是同一件事的两种写法（前端传 ticket），服务端回调靠它认单：
        // 只取其一即可，但两个都要接受——改前端签名不该让原生先崩。
        String extra = call.getString("extra");
        if (extra == null || extra.isEmpty()) extra = call.getString("transId");
        final String ticket = orDefault(extra, "");
        final String rewardName = orDefault(call.getString("rewardName"), "");
        final int rewardAmount = call.getInt("rewardAmount", 0);

        if (ticket.isEmpty()) {
            // 没有工单号的观看请求就算播完也永远结不了算，白白让玩家看一段视频——直接拦下。
            call.setKeepAlive(false);
            call.reject("缺少广告工单号，请重新领取观看资格", "NO_TICKET");
            return;
        }

        if (getActivity() == null) {
            call.setKeepAlive(false);
            call.reject("界面尚未就绪，请稍后再试", "NO_ACTIVITY");
            return;
        }
        // 到这里"同意 + 有密钥 + 有实现"三个前提都已逐个查过了，补一次 init 让首次观看也能起来。
        initIfNeeded();
        // SDK 的加载/展示都要在主线程，插件方法本身也在主线程，这里仍显式切一次：
        // provider 的回调可能来自子线程，runOnUiThread 让"入口在主线程"成为接口约定而非巧合。
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                p.show(getActivity(), spaceId, userId, ticket, rewardName, rewardAmount,
                    new AdProvider.Callback() {
                        @Override
                        public void onResult(boolean finished, int code, String msg) {
                            call.setKeepAlive(false);
                            if (code != 0) {
                                call.reject(msg == null || msg.isEmpty() ? "广告加载失败，稍后再试" : msg,
                                    "AD_ERROR");
                                return;
                            }
                            JSObject ret = new JSObject();
                            ret.put("finished", finished);
                            ret.put("code", 0);
                            if (msg != null) ret.put("msg", msg);
                            call.resolve(ret);
                        }
                    });
            }
        });
    }

    private static String orDefault(String v, String dflt) {
        return v == null || v.isEmpty() ? dflt : v;
    }
}
