package com.chemera.server.game;

import com.chemera.server.mapper.ConfigMapper;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.service.ContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ContentRegistry#cacheWork()}：那本"内容缓存替这条意图干了活"的总账。
 *
 * <p>它只有一个下游用户——意图 SQL 预算（G8）。那条尺子量的是"一次点击发几条 SQL"，可有两种条数
 * 不属于任何一条链：整包重攒（后台改过内容）和版本号 TTL 到期时真去问一次
 * {@code SELECT version FROM content_version}。前者早就被 {@code rebuilds()} 数着了，后者是这一轮补上的：
 * {@code sandbox.exit} 在同一条脚本、同一个服务端上于 6 条与 7 条之间来回跳过，而它的现场只有一次调用，
 * 样本数 = 1，最小值就等于那一次的运气。
 *
 * <p>所以这里钉的是三件事：温缓存连问都不涨总账（预算不会因此变得量不到东西）、
 * 版本号过期的那一次<b>只涨轮询不涨重建</b>（正是漏掉的那一格），以及总账是两者之和而不是取其一。
 */
class ContentCacheWorkTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 只假到 mapper 一层：这条链要验的就是"什么时候真问了库"，桩在 service 上等于把被测那段替掉。 */
    private static ContentMapper content(long version) {
        ContentMapper c = mock(ContentMapper.class);
        when(c.version()).thenReturn(version);
        when(c.allEnabled()).thenReturn(java.util.List.of());
        return c;
    }

    private static ConfigMapper configs() {
        ConfigMapper cfg = mock(ConfigMapper.class);
        when(cfg.allRaw()).thenReturn(java.util.List.of());
        return cfg;
    }

    @Test
    void aWarmCacheMovesNeitherCounter() {
        ContentMapper c = content(9L);
        ContentService svc = new ContentService(c, configs(), OM, 1000L);
        ContentRegistry registry = new ContentRegistry(svc, OM);

        registry.current();
        assertEquals(1L, registry.rebuilds(), "第一次必然攒包");
        assertEquals(1L, svc.versionPolls(), "第一次必然真问库");
        long base = registry.cacheWork();

        for (int i = 0; i < 20; i++) registry.current();
        assertEquals(base, registry.cacheWork(), "1 秒内连问二十次总账不动：那几次意图什么都没多干");
        verify(c, times(1)).version();
    }

    /**
     * 版本号 TTL 到期那一次：多一条 SELECT，但整包不必重攒。
     *
     * <p>{@code versionTtlMs=0} 是把那份缓存关到最严的开关（每次都要问库）。这里特意让版本号保持不变，
     * 于是重建停在 1，只有轮询在涨——如果 {@code cacheWork()} 只读 {@code rebuilds()}，这一格就是隐形的，
     * 预算那侧看到的还是一个"干净样本"，然后按 7 条把一条 6 条的链判超支。
     */
    @Test
    void anExpiredVersionReadCountsAsCacheWorkWithoutARebuild() {
        ContentService svc = new ContentService(content(9L), configs(), OM, 0L);
        ContentRegistry registry = new ContentRegistry(svc, OM);

        registry.current();
        for (int i = 0; i < 3; i++) registry.current();

        assertEquals(1L, registry.rebuilds(), "版本号没变，包不该重攒");
        assertEquals(4L, svc.versionPolls(), "TTL 关掉以后每次都是真读库");
        assertEquals(5L, registry.cacheWork(), "总账 = 1 次重建 + 4 次真读版本号，两个都不能少");
    }

    /** 后台真改了内容（版本号跳号）时，重建与轮询一起涨——总账照样要认这次是脏样本。 */
    @Test
    void aRealContentChangeStillShowsUpAsCacheWork() {
        ContentMapper c = mock(ContentMapper.class);
        when(c.version()).thenReturn(9L, 10L);
        when(c.allEnabled()).thenReturn(java.util.List.of(), java.util.List.of());
        ContentService svc = new ContentService(c, configs(), OM, 0L);
        ContentRegistry registry = new ContentRegistry(svc, OM);

        registry.current();
        long before = registry.cacheWork();
        registry.current();

        assertEquals(2L, registry.rebuilds(), "版本号跳了就该重攒一次");
        assertTrue(registry.cacheWork() > before, "改内容那一下紧跟的意图必须被标成脏样本：" + before + " → " + registry.cacheWork());
    }
}
