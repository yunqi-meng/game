package com.chemera.game.ad;

import android.app.Activity;
import android.app.Application;
import android.util.Log;

import com.chemera.game.AdProvider;
import com.chemera.game.BuildConfig;
// TapADN（Dirichlet SSP）的类。文档给的坐标是 com.tapsdk.tapad 包，
// 各家版本会把 AdRequest / TapAdManager 放在不同子包里，所以这里用整包导入：
// 真机接入时若报 "找不到符号"，只需要改这一行 import，不用碰下面的流程代码。
import com.tapsdk.tapad.*;

/**
 * TapADN 激励视频实现。
 *
 * <p>它**总是参与编译**：没有本地 AAR 时对着 {@code android/tapsdk-stub} 那份同名编译期桩编，
 * 有 AAR 时对着真实 SDK 编（开关与依赖见 app/build.gradle）。这样"SDK 签名对不上"永远是
 * 一条构建期错误，而不是提审前那次意外。运行期它只在 {@code BuildConfig.AD_SDK_BUNDLED=true}
 * 时才被 {@code Providers.ad()} 装载，干净壳里插件照样回 NO_SDK。
 *
 * <p>这份"要么整个类在场、要么整个类缺席"的运行期设计是有意的：广告 SDK 的接口随版本变动，
 * 与其让主流程代码去适配一个可能的旧签名（反射、try-catch、静默降级，最后没人知道哪条路是活的），
 * 不如把不确定性关进一个文件。换 SDK 版本时，编译错误会直接把这个文件指出来——
 * 那是最便宜、也最难被漏掉的位置。
 *
 * <p>参数怎么对应服务端（第 10 章那张工单表）：
 * <pre>
 *   withUserId(userId)   → 回调里的 user_id    （服务端据此认人）
 *   withExtra1(ticket)   → 回调里的 extra      （服务端据此找回这一次观看）
 *   spaceId              → 回调里的 pid        （后台广告位目录里的同一个 id）
 * </pre>
 * trans_id 由平台在播放时生成，我们不碰它；签名由平台用 mediaKey 算，我们在服务端验。
 */
public final class TapAdnProvider implements AdProvider {

    private static final String TAG = "chemera-ad";

    private volatile boolean inited;

    @Override
    public boolean isReady() {
        return inited;
    }

    @Override
    public void init(Application app, String mediaId, String mediaName, String mediaKey) {
        if (inited) return;
        // 到这里一定已经过了隐私同意（ChemeraAdPlugin 判定），这是审核 5.8 要求的顺序，别调换。
        TapAdConfig config = new TapAdConfig.Builder()
            .withMediaId(mediaId)
            .withMediaName(mediaName)
            .withMediaKey(mediaKey)
            .enableDebug(BuildConfig.DEBUG)
            // 摇一摇关掉：本作满屏都是拖拽投放，任何"晃动即跳转"都会被当成误触投诉。
            .shakeEnabled(false)
            .build();
        // SDK 内部在任何设备上都不该把宿主打崩：init 抛了只是"这次没有广告"，
        // 玩家还能正常游戏，进度不会丢；inited 保持 false，界面会把广告位说成"暂不可用"。
        try {
            TapAdSdk.init(app, config);
            inited = true;
            Log.i(TAG, "TapADN 初始化完成（mediaId=" + mediaId + "）");
        } catch (Throwable t) {
            Log.e(TAG, "TapADN 初始化失败：本次会话没有激励视频，其他功能不受影响", t);
        }
    }

    @Override
    public void show(final Activity activity, String spaceId, String userId, final String ticket,
                     String rewardName, int rewardAmount, final Callback callback) {
        // 与 init 同一条底线：SDK 里任何意外都只该让"这一次观看"失败，不该让宿主崩。
        try {
            doShow(activity, spaceId, userId, ticket, rewardName, rewardAmount, callback);
        } catch (Throwable t) {
            Log.e(TAG, "调起激励视频异常", t);
            callback.onResult(false, -1, "广告调起失败：稍后再试，进度不会丢");
        }
    }

    private void doShow(final Activity activity, String spaceId, String userId, final String ticket,
                        String rewardName, int rewardAmount, final Callback callback) {
        final TapAdNative tapAdNative = TapAdManager.get().createAdNative(activity);
        AdRequest request = new AdRequest.Builder()
            .withSpaceId(spaceId)
            .withRewardName(rewardName)
            // 文档里 withRewardAmount 收的是字符串；若手上这版 SDK 是 int 重载，直接传 rewardAmount。
            .withRewardAmount(String.valueOf(rewardAmount))
            .withExtra1(ticket)
            .withUserId(userId)
            .build();

        tapAdNative.loadRewardVideoAd(request, new TapAdNative.RewardVideoAdListener() {
            @Override
            public void onError(int code, String message) {
                Log.w(TAG, "激励视频加载失败 code=" + code + " msg=" + message);
                callback.onResult(false, code == 0 ? -1 : code, message);
            }

            @Override
            public void onRewardVideoAdLoad(TapRewardVideoAd ad) {
                // 文档建议在这里不展示：素材还没缓存好，立刻展示会黑屏一下。
            }

            @Override
            public void onRewardVideoCached(TapRewardVideoAd ad) {
                if (ad == null) {
                    callback.onResult(false, -1, "广告素材为空");
                    return;
                }
                bindAndShow(activity, ad, callback);
            }
        });
    }

    /**
     * 交互回调 → 一个结论。
     *
     * <p>{@code finished} 取 onRewardVerify 的 verdict，而不是 onVideoComplete：跳过按钮之后
     * 平台仍可能判定有效（不同策略），也可能播完了却不给奖（未有效曝光）。真正的发奖还是等服务端
     * 收到 SSV 回调再结算，这里只决定前端那句提示是"奖励稍后到账"还是"未看完，本次不发奖"。
     */
    private void bindAndShow(Activity activity, TapRewardVideoAd ad, final Callback callback) {
        final boolean[] verified = new boolean[] { false };
        final boolean[] answered = new boolean[] { false };
        ad.setRewardAdInteractionListener(new TapRewardVideoAd.RewardAdInteractionListener() {
            @Override public void onAdShow() {}
            @Override public void onAdClick() {}
            @Override public void onAdValidShow() {}
            @Override public void onVideoError() {
                if (answered[0]) return;
                answered[0] = true;
                callback.onResult(false, -1, "广告播放出错");
            }
            @Override public void onSkippedVideo() {}
            @Override public void onVideoComplete() {}
            @Override public void onRewardVerify(boolean rewardVerify, int amount, String name, int code, String msg) {
                verified[0] = rewardVerify;
                if (rewardVerify) return;
                Log.i(TAG, "平台判定本次观看无效：code=" + code + " msg=" + msg);
            }
            @Override public void onAdClose() {
                if (answered[0]) return;
                answered[0] = true;
                callback.onResult(verified[0], 0, verified[0] ? null : "未看完广告，本次不发奖");
            }
        });
        ad.showRewardVideoAd(activity);
    }
}
