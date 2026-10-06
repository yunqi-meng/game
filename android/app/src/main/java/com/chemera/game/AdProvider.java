package com.chemera.game;

import android.app.Activity;
import android.app.Application;

/**
 * 广告播放能力的宿主侧接口 —— 它存在的唯一理由是"SDK 不一定在这个包里"。
 *
 * <p>TapADN 的 SDK 是平台发的本地 AAR，不进仓库（见 app/build.gradle 里的 libs/*.aar 开关），
 * 所以实现类 {@code com.chemera.game.ad.TapAdnProvider} 只在放了 AAR 的构建里才被编译。
 * 本接口留在主源码集里，插件对它的调用永远是同一套形状，找不到实现就退回"广告不可用"。
 *
 * <p>之所以在原生侧还要留这么一层薄接口，而不是让插件直接反射调 SDK：反射写出来的广告流程
 * 没人敢维护（回调顺序、线程、Activity 生命周期全是隐式约定），而一个接口 + 一个实现文件
 * 是可以在真机上读着改对的。
 */
public interface AdProvider {

    /**
     * 播放结果。
     *
     * <p>{@code finished} 只是"看完可发奖"的提示：真正的发奖凭据是广告平台服务器打给我们服务端的
     * SSV 回调，客户端这条回调不作任何凭据（{@code code != 0} 表示加载/播放出错，由插件转成 reject）。
     */
    interface Callback {
        void onResult(boolean finished, int code, String msg);
    }

    /** SDK 是否已完成初始化（初始化必须发生在隐私同意之后，由调用方把关）。 */
    boolean isReady();

    /** 主线程调用；实现内部要保证幂等，重复 init 直接吞掉。 */
    void init(Application app, String mediaId, String mediaName, String mediaKey);

    /**
     * 加载并播放一条激励视频，必须在主线程调用。
     *
     * @param spaceId      广告位 id（由服务端随工单下发，跟后台目录一致；空则用构建期兜底值）
     * @param userId       本服用户 id：写进 SDK，再原样出现在 SSV 回调里，服务端据此认人
     * @param ticket       服务端签发的工单号：作为 extra 传下去，回调靠它找回这一次观看
     * @param rewardName   奖品名（平台侧展示与校验用）
     * @param rewardAmount 奖品数量
     */
    void show(Activity activity, String spaceId, String userId, String ticket,
              String rewardName, int rewardAmount, Callback callback);
}
