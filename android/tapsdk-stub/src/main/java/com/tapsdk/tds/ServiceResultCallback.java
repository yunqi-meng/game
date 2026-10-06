package com.tapsdk.tds;

/** 编译期桩（非真实 SDK）：provider 用的是两个类型参数的写法（结果类型 + 错误类型）。 */
public interface ServiceResultCallback<T, E> {
    void onResult(T result);
    void onError(E error);
}
