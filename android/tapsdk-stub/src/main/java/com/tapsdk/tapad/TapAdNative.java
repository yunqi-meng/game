package com.tapsdk.tapad;

/** 编译期桩（非真实 SDK）。回调接口的方法集按平台文档镜像，签名不对时编译会在这里报错。 */
public interface TapAdNative {
    void loadRewardVideoAd(AdRequest request, RewardVideoAdListener listener);

    interface RewardVideoAdListener {
        void onError(int code, String message);
        void onRewardVideoAdLoad(TapRewardVideoAd ad);
        void onRewardVideoCached(TapRewardVideoAd ad);
    }
}
