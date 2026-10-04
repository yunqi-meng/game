package com.chemera.server.common;

/** 业务异常：携带对外可读信息，由全局异常处理器转为 ApiResponse。 */
public class BizException extends RuntimeException {
    public final int code;
    public BizException(String msg) { this(400, msg); }
    public BizException(int code, String msg) { super(msg); this.code = code; }

    public static BizException notFound(String what) { return new BizException(404, what + "不存在"); }
    public static BizException forbidden(String msg) { return new BizException(403, msg); }
    public static BizException unauthorized(String msg) { return new BizException(401, msg); }
    public static BizException tooMany(String msg) { return new BizException(429, msg); }
}
