package com.chemera.server.stats;

import com.chemera.server.game.ContentRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.function.LongSupplier;

/**
 * 把每条游戏意图的耗时与 SQL 条数记进 {@link IntentMetrics}（G8）。
 *
 * <p>为什么单独一个拦截器而不是塞进 {@code RequestTraceInterceptor}：追踪器管的是"这一次请求是谁、
 * 花了多久、日志怎么写"，它对所有路径生效；这里只想统计意图，而且必须在 MyBatis 已经跑完之后、
 * 线程归还给容器之前把 ThreadLocal 清掉。两件事凑在一个类里，将来谁改日志都会不小心动到统计口径。
 *
 * <p>{@code SqlCounter.end()} 放在 {@code afterCompletion} 的 finally：Tomcat 会复用线程，
 * 漏一次清理，下一个请求就会把上一个人的条数加到自己头上——那种数字比没有数字更害人。
 *
 * <p>第二个职责：判断这一次有没有<b>顺带重建内容快照</b>。做法是问注册表"重建计数器现在几了"，
 * 前后不一就是涨了——把这件事标进统计，预算那条尺子才量得准（见 {@link IntentMetrics#record}）。
 * 这里刻意只认一个 {@link LongSupplier} 而不是直接依赖 {@code ContentRegistry} 的行为：
 * 拦截器要的是"一个会涨的数"，测试好喂，将来换成别的缓存实现也不用改这一层。
 */
@Component
public class IntentTraceInterceptor implements HandlerInterceptor {

    private static final String AT = "chemera.intent.start";
    private static final String RB = "chemera.intent.rebuilds";
    private static final String GAME = "/api/game/";

    private final IntentMetrics metrics;
    private final LongSupplier rebuilds;

    /**
     * 容器走这条。{@code @Autowired} 不是装饰：这里有两个构造（下面还有个测试入口），
     * Spring 只在"恰好一个构造"时才自己猜，多一个就得写明用哪个，否则退回去找无参构造——
     * 启动时表现为 {@code No default constructor found}，整条意图统计跟着起不来。
     */
    @Autowired
    public IntentTraceInterceptor(IntentMetrics metrics, ContentRegistry registry) {
        this(metrics, registry::rebuilds);
    }

    /** 测试用入口：给一个会涨的计数器就行。 */
    IntentTraceInterceptor(IntentMetrics metrics, LongSupplier rebuilds) {
        this.metrics = metrics;
        this.rebuilds = rebuilds;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
        if (!worth(req)) return true;
        req.setAttribute(AT, System.nanoTime());
        req.setAttribute(RB, rebuilds.getAsLong());
        SqlCounter.begin();
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object h, Exception ex) {
        Object st = req.getAttribute(AT);
        if (st == null) return;
        try {
            long ms = (System.nanoTime() - (Long) st) / 1_000_000;
            Object before = req.getAttribute(RB);
            boolean rebuilt = before instanceof Long b && rebuilds.getAsLong() != b;
            metrics.record(req.getRequestURI().substring(GAME.length()), ms, SqlCounter.snapshot(), rebuilt);
        } finally {
            SqlCounter.end();
        }
    }

    /** 只数意图：静态资源、登录页、后台列表都各有自己的成本结构，混进同一张表只会看不出问题。 */
    private static boolean worth(HttpServletRequest req) {
        String p = req.getRequestURI();
        return p.startsWith(GAME) && p.length() > GAME.length();
    }
}
