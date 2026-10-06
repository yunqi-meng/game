package com.chemera.server.stats;

/**
 * 一次请求里发了几条 SQL：线程内的计数器（G8 的观测面）。
 *
 * <p>为什么不用 micrometer：本项目整个观测面就三个数字（意图耗时、SQL 条数、连接池水位），
 * 引一个指标框架换来的是依赖、配置与一套没人熟的术语。这里三行 ThreadLocal 就够。
 *
 * <p>{@code begin()} 与 {@code snapshot()} 由 {@link IntentTraceInterceptor} 在请求首尾调用，
 * 中间 {@code SqlCountInterceptor} 每见一条语句就 {@code inc()}。没 begin 过时 {@code inc} 是空操作——
 * 后台线程、定时任务本来就不该被算进任何意图的条数里。
 */
public final class SqlCounter {

    private static final ThreadLocal<int[]> CURRENT = new ThreadLocal<>();

    private SqlCounter() { }

    public static void begin() { CURRENT.set(new int[1]); }

    public static void inc() {
        int[] c = CURRENT.get();
        if (c != null) c[0]++;
    }

    /** 本次请求已发出的语句条数；没在计数中返回 -1（调用方据此判断"这条指标不该出现")。 */
    public static int snapshot() {
        int[] c = CURRENT.get();
        return c == null ? -1 : c[0];
    }

    public static void end() { CURRENT.remove(); }
}
