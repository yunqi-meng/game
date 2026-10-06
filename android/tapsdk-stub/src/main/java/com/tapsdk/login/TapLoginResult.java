package com.tapsdk.login;

/** 编译期桩（非真实 SDK）：getKid()/getMacKey() 就是服务端 TapTapVerifier 要的 MAC 验签两要素。 */
public class TapLoginResult {
    public long kid;
    public String macKey;

    public long getKid() { return kid; }
    public String getMacKey() { return macKey; }
}
