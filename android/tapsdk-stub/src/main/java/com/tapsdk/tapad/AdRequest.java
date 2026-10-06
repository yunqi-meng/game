package com.tapsdk.tapad;

/** 编译期桩（非真实 SDK）。 */
public class AdRequest {
    public String spaceId, rewardName, rewardAmount, extra1, userId;

    public static class Builder {
        private final AdRequest r = new AdRequest();
        public Builder withSpaceId(String v) { r.spaceId = v; return this; }
        public Builder withRewardName(String v) { r.rewardName = v; return this; }
        public Builder withRewardAmount(String v) { r.rewardAmount = v; return this; }
        public Builder withExtra1(String v) { r.extra1 = v; return this; }
        public Builder withUserId(String v) { r.userId = v; return this; }
        public AdRequest build() { return r; }
    }
}
