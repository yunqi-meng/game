package com.chemera.server.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 统一响应体：所有 API 返回 {ok, code, data, msg}。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    public boolean ok;
    public int code;
    public T data;
    public String msg;

    public ApiResponse() {}

    public ApiResponse(boolean ok, int code, T data, String msg) {
        this.ok = ok; this.code = code; this.data = data; this.msg = msg;
    }

    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, 0, data, null); }
    public static <T> ApiResponse<T> ok() { return new ApiResponse<>(true, 0, null, null); }
    public static <T> ApiResponse<T> err(int code, String msg) { return new ApiResponse<>(false, code, null, msg); }
}
