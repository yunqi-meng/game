package com.chemera.server.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 统一响应体：所有 API 返回 {ok, code, data, msg}，需要客户端分流时多带一个 tag。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    public boolean ok;
    public int code;
    public T data;
    public String msg;
    /** 机器可读标签（见 {@link BizException#tag}）。绝大多数响应没有它，NON_NULL 保证老客户端看到的形状不变。 */
    public String tag;

    public ApiResponse() {}

    public ApiResponse(boolean ok, int code, T data, String msg) {
        this.ok = ok; this.code = code; this.data = data; this.msg = msg;
    }

    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, 0, data, null); }
    public static <T> ApiResponse<T> ok() { return new ApiResponse<>(true, 0, null, null); }
    public static <T> ApiResponse<T> err(int code, String msg) { return new ApiResponse<>(false, code, null, msg); }

    public ApiResponse<T> tagged(String t) { this.tag = t; return this; }
}
