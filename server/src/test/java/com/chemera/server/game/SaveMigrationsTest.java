package com.chemera.server.game;

import com.chemera.server.common.BizException;
import com.chemera.server.service.SaveService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 存档结构版本位（A4）的回归。
 *
 * <p>这里守的不是功能，是"改字段名会不会静默毁档"这件永远不会在测试环境里发生、
 * 只会在玩家身上发生的事。{@code GameState} 带着 ignoreUnknown，缺字段一律填 Java 默认值，
 * 所以一旦哪天 {@code ad.points} 被改名成 {@code ad.credit}，没有版本位的读盘路径会安静地
 * 把老玩家的积分读成 0，再在下一次写盘时把 0 落回库里——丢的东西连日志都没有。
 * 下面这几条断言把三件事钉死：写盘那一帧原样递出去（版本位由存档层那道门盖）、旧档载入必走迁移、
 * 比本机新的档必须被拒而不是被降级。
 */
class SaveMigrationsTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /* ---------------- 脚手架 ---------------- */

    /** 只 mock 存档读写边界，其余（转换、迁移、版本位）都走 DbGameStore 的真实代码。 */
    private static DbGameStore storeWith(SaveService saves) { return new DbGameStore(saves, OM); }

    /** 造一份"库里已有的 payload"：SaveService.get 返回的是解析过的 Map，所以这里也必须是 Map。 */
    private static Map<String, Object> existing(String payloadJson) throws Exception {
        Map<String, Object> m = OM.readValue(payloadJson, new TypeReference<LinkedHashMap<String, Object>>() {});
        return Map.of("exists", true, "payload", m);
    }

    /** 一份早于版本位的老存档：有 v=2、有金币，但没有 sv，也没有 ad 那一段。 */
    private static String legacyPayload() {
        return "{\"v\":2,\"coins\":8888,\"level\":7,\"bag\":{\"H2|0\":3},\"discovered\":{},"
                + "\"daily\":{\"claimed\":{}},\"monthly\":{\"until\":0},\"challenge\":{\"revive\":0}}";
    }

    /** 指定 sv 的存档，用来试"上一版""未来版""脏值"三种输入。 */
    private static String payloadWithSv(String sv, long coins) {
        return "{\"v\":2,\"sv\":" + sv + ",\"coins\":" + coins + ",\"bag\":{},\"discovered\":{},"
                + "\"daily\":{\"claimed\":{}},\"monthly\":{\"until\":0},\"challenge\":{\"revive\":0}}";
    }

    /**
     * 写盘交出去的那一帧。
     *
     * <p>G8 之后这里是 {@link GameState} 对象本身而不是中间 JSON 树：{@code DbGameStore} 不再
     * {@code valueToTree}，结构自检由 {@code GameState.defect()} 答，版本位由存档层写盘时盖。
     * 所以这个 capture 点数的正是"store 把同一个对象原样递给了存档层"。
     */
    private static GameState capturePut(SaveService saves) {
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(saves).put(anyLong(), cap.capture(), isNull(), eq(true));
        return (GameState) cap.getValue();
    }

    /* ---------------- 1. 写盘：版本位归存档层那道门，不归 store ---------------- */

    /**
     * store 交出去的就是调用方手里那个对象，自己不再碰版本位（G8）。
     *
     * <p>"每一次写盘必带当前版本位"这条不变量还在，只是**守它的地方换了**：以前是 {@code DbGameStore}
     * 在递出去之前 {@code g.sv = SCHEMA_VERSION}，于是任何新加的写入方只要漏掉这一步，库里就多一帧
     * {@code sv} 为 null 的存档——载入时会把它当成"早于版本位的老档"走补历史那支，而不是"刚由本机这版写的"。
     * 现在盖章收进了 {@code SaveService} 写盘那道门（{@code SaveServiceTest} 的
     * {@code typedFrameLandsAsTheSameJsonTheTreePathWouldHaveWritten} 用真存档层钉住它），
     * 漏盖这件事没有发生的余地。于是这里断言的是另一半：store 既不抢先盖，也不换一个对象递出去。
     */
    @Test
    void theStoreHandsTheSameFrameOverWithoutTouchingTheBit() {
        SaveService saves = mock(SaveService.class);
        when(saves.put(anyLong(), any(), isNull(), eq(true)))
                .thenReturn(Map.of("ok", true, "revision", 12L));
        DbGameStore store = storeWith(saves);

        GameState g = new GameState();
        assertNull(g.sv, "新建的对象不该自带版本位");
        assertEquals(12L, store.save(9L, g));

        assertSame(g, capturePut(saves), "中间那棵树没了：递出去的必须是同一个对象");
        assertNull(g.sv, "盖章归写盘那道门；store 抢先盖一次，两条路就有了两种口径");
    }

    @Test
    void roundTripKeepsTheBitAndTheNumbers() throws Exception {
        SaveService saves = mock(SaveService.class);
        when(saves.put(anyLong(), any(), isNull(), eq(true))).thenReturn(Map.of("revision", 1L));
        DbGameStore store = storeWith(saves);

        GameState g = new GameState();
        g.coins = 4321;
        g.bag.put("Fe|1", 2);
        store.save(3L, g);

        GameState written = capturePut(saves);
        // 按协作者的合同补上那一章（真实现里由 SaveService 在序列化前盖）：
        // 少了这一步，下面那次读盘走的就是"早于版本位"那支，往返测的就不是往返了。
        written.stampSchemaVersion();
        when(saves.get(3L)).thenReturn(Map.of("exists", true,
                "payload", OM.convertValue(written, new TypeReference<LinkedHashMap<String, Object>>() {})));

        GameState back = store.load(3L).orElseThrow();
        assertEquals(4321, back.coins);
        assertEquals(2, back.bag.get("Fe|1"));
        assertEquals(GameState.SCHEMA_VERSION, back.sv);
    }

    /* ---------------- 2. 读盘：老存档按版本补齐，而不是填满默认值 ---------------- */

    @Test
    void legacyPayloadWithoutTheBitGetsTheAdBlockBackfilled() throws Exception {
        SaveService saves = mock(SaveService.class);
        when(saves.get(1L)).thenReturn(existing(legacyPayload()));

        GameState g = storeWith(saves).load(1L).orElseThrow();

        assertNotNull(g.ad, "早于版本位的存档没有 ad 段：读盘这一刻就得补齐，不能指望每条派发路径记得 ensure");
        assertNotNull(g.ad.perDay);
        assertNotNull(g.ad.lastAt);
        assertNotNull(g.ad.redeemed);
        assertEquals(8888, g.coins, "迁移只补结构，不许动玩家已有的数值");
        assertEquals(7, g.level);
        assertEquals(GameState.SCHEMA_VERSION, g.sv, "补完要立刻盖章，下次读就只走增量迁移");
    }

    /** 读盘时版本位必须在 convertValue 之前取：转换后"没有 sv"和"sv=2"会变成同一个 null。 */
    @Test
    void anExplicitOldBitMigratesWhileCurrentOnesDoNotRewriteAnything() throws Exception {
        SaveService saves = mock(SaveService.class);
        when(saves.get(2L)).thenReturn(existing(payloadWithSv(String.valueOf(GameState.SCHEMA_VERSION - 1), 1)));
        GameState old = storeWith(saves).load(2L).orElseThrow();
        assertEquals(GameState.SCHEMA_VERSION, old.sv);
        assertNotNull(old.ad, "上一版同样要补 ad");

        SaveService saves2 = mock(SaveService.class);
        when(saves2.get(3L)).thenReturn(existing(payloadWithSv(String.valueOf(GameState.SCHEMA_VERSION), 1)));
        GameState now = storeWith(saves2).load(3L).orElseThrow();
        assertEquals(GameState.SCHEMA_VERSION, now.sv);
    }

    @Test
    void alreadyMigratedDataIsLeftAlone() {
        GameState g = new GameState();
        g.ad = new GameState.Ad();
        g.ad.points = 60;
        g.ad.revive = 2;

        SaveMigrations.apply(g, 2);

        assertEquals(60, g.ad.points, "ensure 只补 null 容器，不许重置已攒下的积分");
        assertEquals(2, g.ad.revive);
    }

    /** 脏数据（sv 是字符串/负数/0）当作"早于版本位"处理：能玩总比报错好，且一定会被重新盖章。 */
    @Test
    void aBogusBitIsTreatedAsLegacyInsteadOfCrashing() throws Exception {
        SaveService saves = mock(SaveService.class);
        when(saves.get(4L)).thenReturn(existing(payloadWithSv("\"3\"", 5)));

        GameState g = storeWith(saves).load(4L).orElseThrow();
        assertEquals(GameState.SCHEMA_VERSION, g.sv);
        assertNotNull(g.ad);
        assertEquals(5, g.coins);
    }

    /* ---------------- 3. 反向：比本机新的存档必须拒绝，而不是"按本机理解写回去" ---------------- */

    @Test
    void aNewerPayloadIsRefusedRatherThanSilentlyFlattened() throws Exception {
        SaveService saves = mock(SaveService.class);
        when(saves.get(5L)).thenReturn(existing(payloadWithSv("99", 7777)));

        BizException e = assertThrows(BizException.class, () -> storeWith(saves).load(5L));
        assertEquals(403, e.code);
        assertTrue(e.getMessage().contains("99"), "文案要带上两个版本号，否则客服只看得到'载入失败'：" + e.getMessage());
        // 拒载的一帧绝不能被写回去。这里用 any() 而不是 any(JsonNode.class)：
        // 现在写盘递出去的是 GameState，带类型的匹配器会"匹配不上"从而让这条 never() 白过。
        verify(saves, never()).put(anyLong(), any(), any(), anyBoolean(), any());
        verify(saves, never()).put(anyLong(), any(), isNull(), anyBoolean());
    }

    @Test
    void applyRejectsAFutureVersionDirectly() {
        BizException e = assertThrows(BizException.class,
                () -> SaveMigrations.apply(new GameState(), GameState.SCHEMA_VERSION + 1));
        assertEquals(403, e.code);
    }

    /** 0 与 null 走同一分支（都按"早于版本位"处理），这样老库里的 0 值不会变成漏迁移的死角。 */
    @Test
    void preVersionBitAndZeroAreTheSameCase() {
        GameState a = new GameState();
        GameState b = new GameState();
        SaveMigrations.apply(a, null);
        SaveMigrations.apply(b, 0);
        assertEquals(GameState.SCHEMA_VERSION, a.sv);
        assertEquals(a.sv, b.sv);
        assertNotNull(a.ad);
        assertNotNull(b.ad);
    }
}
