package com.tapsdk.tds;

/** 编译期桩（非真实 SDK）：只镜像 provider 用到的那一小段表面。 */
public class TDSError extends Exception {
    private static final long serialVersionUID = 1L;
    public int code;

    public TDSError() { super("TapSDK 未内置（编译期桩）"); }
    public TDSError(String message) { super(message); }
    public int getCode() { return code; }
}
