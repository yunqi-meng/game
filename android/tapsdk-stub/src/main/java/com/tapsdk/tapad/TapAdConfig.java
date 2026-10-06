package com.tapsdk.tapad;

/** 编译期桩：字段与 builder 方法与 TapADN 文档一致，实现一律抛"未内置"。见 tapsdk-stub/build.gradle。 */
public class TapAdConfig {
    public String mediaId, mediaName, mediaKey;
    public boolean debug, shakeEnabled;

    public static class Builder {
        private final TapAdConfig c = new TapAdConfig();
        public Builder withMediaId(String v) { c.mediaId = v; return this; }
        public Builder withMediaName(String v) { c.mediaName = v; return this; }
        public Builder withMediaKey(String v) { c.mediaKey = v; return this; }
        public Builder enableDebug(boolean v) { c.debug = v; return this; }
        public Builder shakeEnabled(boolean v) { c.shakeEnabled = v; return this; }
        public TapAdConfig build() { return c; }
    }
}
