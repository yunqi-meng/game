package com.chemera.server.service;

import com.chemera.server.mapper.ConfigMapper;
import com.chemera.server.mapper.ContentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容下发：把 DB 中的 content_item + app_config 组装成客户端一次性拉取的 bundle，按版本号缓存。
 *
 * <p>{@code bundle()} 自己已经按 {@code content_version} 缓存了，但"问一版本号"这件事以前是
 * **每请求一次**：{@code ContentRegistry.current()} 在每条意图里都要调它，于是热路径上凭空多一条
 * {@code SELECT}。版本号现在也缓存（默认 1 秒），理由和 bundle 缓存是同一条——内容热更新本来就是
 * 分钟级的人工操作，为了 1 秒的新鲜度让全国玩家每人每条意图多打一次库，不划算。
 * 代价是"别的进程改了内容，本机最多晚 1 秒才看见"；同进程后台改词走 {@link #invalidate()}
 * 是立刻生效的（它会把版本号和 bundle 一起清掉，见那个方法）。
 */
@Service
public class ContentService {

    /** 版本号读数的新鲜度上限（毫秒）。给 0 就等于关掉缓存，回归测试里用它来观察真实查询。 */
    private final long versionTtlMs;

    private final ContentMapper content;
    private final ConfigMapper config;
    private final ObjectMapper om;
    private volatile long cachedVersion = -1;
    private volatile Map<String, Object> cachedBundle;

    /** 上一次读到的 {@code content_version} 与读到它的时刻；{@code readAt=0} 表示还没有过。 */
    private volatile long readVersion;
    private volatile long readAt;

    /** Spring 装配入口；三参那个只给单测直接拼，故显式标注首选构造。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ContentService(ContentMapper c, ConfigMapper cfg, ObjectMapper om,
                          @org.springframework.beans.factory.annotation.Value("${chemera.content.version-cache-ms:1000}") long versionTtlMs) {
        this.content = c; this.config = cfg; this.om = om;
        this.versionTtlMs = Math.max(0L, versionTtlMs);
    }

    ContentService(ContentMapper c, ConfigMapper cfg, ObjectMapper om) {
        this(c, cfg, om, 1000L);
    }

    /** 当前内容版本：1 秒内的重复请求直接回缓存里那个数，不再问数据库。 */
    private long version() {
        long now = System.currentTimeMillis();
        long at = readAt;
        if (at != 0 && now - at < versionTtlMs) return readVersion;
        long v = content.version();
        readVersion = v;
        readAt = now;
        return v;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> bundle() {
        long v = version();
        Map<String, Object> local = cachedBundle;
        if (v == cachedVersion && local != null) return local;
        synchronized (this) {
            if (v == cachedVersion && cachedBundle != null) return cachedBundle;
            Map<String, List<Object>> grouped = new LinkedHashMap<>();
            for (var it : content.allEnabled()) {
                try {
                    Map<String, Object> node = om.readValue(it.getData(), Map.class);
                    String type = it.getContentType();
                    grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(node);
                } catch (Exception ignored) {}
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            for (var row : config.allRaw()) {
                try {
                    cfg.put((String) row.get("cfg_key"), om.readValue(String.valueOf(row.get("cfg_value")), Object.class));
                } catch (Exception ignored) {}
            }
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("version", v);
            bundle.put("content", grouped);
            bundle.put("config", cfg);
            cachedBundle = bundle; cachedVersion = v;
            return bundle;
        }
    }

    /**
     * 后台改完内容调它：bundle 与版本号读数一起作废。
     *
     * <p>版本号那份 1 秒缓存在这里必须一起清，否则会出现"内容已经是新的、包里标的版本还是旧的"——
     * 客户端拿 {@code version} 做缓存判别，标错了就是一包新数据顶着旧号被丢掉。
     */
    public void invalidate() {
        cachedVersion = -1;
        readAt = 0L;
    }

    /**
     * 分片体检：每个内容类型有多少条、序列化后占多少字节，按体积从大到小排。
     *
     * <p>为什么要有这个接口：bundle 是启动链上最大的一次下载，"该不该再拆一片"这种决定
     * 不能靠猜。实测过一轮（118 元素 / 143 方程式）：方程式 46KB、元素 26KB、化合物 23KB，
     * 三者占整包的 88%，而它们全是实验台首屏就要用的——按类型分片最多省下其余的 6%，
     * 换来的是"面板可能画到旧种子"的不确定性。所以这轮做的是"命中缓存就别等网络"，
     * 而把这个接口留下来，让下次有人提分片时先读一眼数字。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> shards() {
        Map<String, Object> b = bundle();
        List<Map<String, Object>> out = new ArrayList<>();
        long total = 0;
        Object content = b.get("content");
        if (content instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) content).entrySet()) {
                List<Object> rows = e.getValue() instanceof List ? (List<Object>) e.getValue() : List.of();
                long bytes = bytes(rows);
                total += bytes;
                out.add(row(e.getKey(), rows.size(), bytes));
            }
        }
        Map<String, Object> cfg = b.get("config") instanceof Map ? (Map<String, Object>) b.get("config") : Map.of();
        long cfgBytes = bytes(cfg);
        out.add(row("config", cfg.size(), cfgBytes));
        out.sort((x, y) -> Long.compare((Long) y.get("bytes"), (Long) x.get("bytes")));
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("version", cachedVersion);
        rep.put("bytes", total + cfgBytes);
        rep.put("shards", out);
        return rep;
    }

    private long bytes(Object o) {
        try { return om.writeValueAsBytes(o).length; } catch (Exception e) { return 0; }
    }

    private static Map<String, Object> row(String type, int count, long bytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type); m.put("count", count); m.put("bytes", bytes);
        return m;
    }
}
