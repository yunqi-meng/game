package com.tapsdk.tapad;

import android.app.Activity;

/** 编译期桩（非真实 SDK）。 */
public interface TapRewardVideoAd {
    void setRewardAdInteractionListener(RewardAdInteractionListener listener);
    void showRewardVideoAd(Activity activity);

    interface RewardAdInteractionListener {
        void onAdShow();
        void onAdClick();
        void onAdValidShow();
        void onVideoError();
        void onSkippedVideo();
        void onVideoComplete();
        void onRewardVerify(boolean rewardVerify, int amount, String name, int code, String msg);
        void onAdClose();
    }
}
