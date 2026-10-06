package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.game.GameState;
import com.chemera.server.mapper.SaveMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 存档写回这一层的回归：CAS（{@link SaveService#putCas}）与历史抽样（{@code historyWorth}）。
 *
 * <p>为什么单独立一个文件而不塞进 {@code SaveMigrationsTest}：那边守的是"读盘认不认得出版本"，
 * 这边守的是"写盘会不会覆盖别人、以及出事以后历史里翻不翻得到"。两件事的失败长相完全不同——
 * 前者是玩家档案读坏了，后者是玩家的东西被静默抹掉。
 *
 * <p>Mapper 全部 mock：这里要钉的就是发给数据库的那几句 SQL 长什么样（带不带号、0 行时动不动历史表），
 * 真库那条路径由 e2e 从 HTTP 侧覆盖。
 */
class SaveServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final long MAX = 2_097_152L;

    private static SaveService svc(SaveMapper m, long historyEvery) {
        return new SaveService(m, OM, MAX, historyEvery);
    }

    /** 一份能过 validate 的最小 payload；coins 随参数变，方便断言"写进去的到底是哪一帧"。 */
    private static JsonNode frame(long coins) {
        return OM.valueToTree(Map.of("v", 2, "coins", coins, "level", 1,
                "bag", Map.of(), "discovered", Map.of()));
    }

    /* ---------------- 1. CAS：抢输的一方一行都不许写 ---------------- */

    @Test
    void firstFrameOfAnAccountIsCreatedWithRevisionOne() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.insertFirst(eq(7L), anyString())).thenReturn(1);          // 库里没有这行，我们建出来了

        Map<String, Object> r = svc(m, 25).putCas(7L, frame(5000), 0, SaveService.SOURCE_INTENT);

        assertEquals(true, r.get("ok"), "首帧写入应成功：" + r);
        assertEquals(1L, r.get("revision"));
        verify(m, never()).casPut(anyLong(), anyString(), anyLong());
        verify(m).addRevision(argThat(x -> x.getRevision() == 1L
                && SaveService.SOURCE_INTENT.equals(x.getSource())));
    }

    @Test
    void aStaleBaseWritesNothingAndReportsTheCurrentRevision() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.casPut(eq(7L), anyString(), eq(3L))).thenReturn(0);       // 库里已经不是第 3 版：抢输
        when(m.currentRevision(7L)).thenReturn(9L);

        Map<String, Object> r = svc(m, 25).putCas(7L, frame(123), 3, SaveService.SOURCE_INTENT);

        assertEquals(false, r.get("ok"), "撞号必须回冲突，而不是假装写成功");
        assertEquals(true, r.get("conflict"));
        assertEquals(9L, r.get("revision"), "顺手把库里现在的号带回去，调用方重读时不用再看一眼");
        verify(m, never()).addRevision(any());
        verify(m, never()).trimRevisionsBefore(anyLong(), anyLong());
    }

    @Test
    void aWinningCasBumpsExactlyOneAndPersistsThePayloadWeHandedIn() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.casPut(eq(7L), anyString(), eq(4L))).thenReturn(1);

        Map<String, Object> r = svc(m, 25).putCas(7L, frame(4321), 4, SaveService.SOURCE_INTENT);

        assertEquals(true, r.get("ok"));
        assertEquals(5L, r.get("revision"), "号只能加一：加多了说明这条路径偷偷写了两次");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(m).casPut(eq(7L), json.capture(), eq(4L));
        assertTrue(json.getValue().contains("4321"), "落盘的得是我们给的这一帧：" + json.getValue());
    }

    /**
     * 建号那句只能在 {@code base == 0} 时出现，这条不是洁癖而是被死锁教出来的。
     *
     * <p>回归现场（e2e 四路并发买滤粉，日志里 InnoDB 报得清清楚楚）：两个事务都在
     * {@code user_save} 主键上"持有 lock mode S、等待 lock_mode X"——S 锁来自 {@code INSERT IGNORE}
     * 撞重复键时的唯一性检查，X 锁来自紧接着的 {@code casPut}。各自握着对方的升级目标，
     * InnoDB 挑一个整个回滚，玩家收到 500。库里既然已经有行（号都读到非零了），
     * 那次 INSERT 除了留一个 S 锁什么也不干，去掉它死锁就没了。
     */
    @Test
    void onlyAMissingFrameAsksTheDatabaseToCreateTheRow() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.casPut(anyLong(), anyString(), anyLong())).thenReturn(1);

        svc(m, 25).putCas(7L, frame(100), 41, SaveService.SOURCE_INTENT);        // 号非零：行早就在

        verify(m, never()).insertFirst(anyLong(), anyString());
        verify(m).casPut(eq(7L), anyString(), eq(41L));

        SaveMapper fresh = mock(SaveMapper.class);
        when(fresh.insertFirst(eq(7L), anyString())).thenReturn(1);
        svc(fresh, 25).putCas(7L, frame(5000), 0, SaveService.SOURCE_INTENT);     // 读帧时没人：该建号
        verify(fresh).insertFirst(eq(7L), anyString());
    }

    /** 两个人同时读到"还没有存档"，只有一个建号成功；另一个必须走冲突重放，而不是写坏对方那一帧。 */
    @Test
    void twoConcurrentFirstFramesOneBuildsTheRowTheOtherBacksOff() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.insertFirst(anyLong(), anyString())).thenReturn(0);                // 抢建号输了
        when(m.casPut(anyLong(), anyString(), anyLong())).thenReturn(0);          // base=0 也配不上任何行
        when(m.currentRevision(7L)).thenReturn(1L);

        Map<String, Object> r = svc(m, 25).putCas(7L, frame(5000), 0, SaveService.SOURCE_INTENT);

        assertEquals(true, r.get("conflict"), "建号输了就退回冲突，让上层重读那一帧再重放");
        assertEquals(1L, r.get("revision"));
        verify(m, never()).addRevision(any());
    }

    @Test
    void casRefusesAnIllegalFrameBeforeTouchingTheDatabase() {
        SaveMapper m = mock(SaveMapper.class);
        SaveService s = svc(m, 25);
        JsonNode bad = OM.valueToTree(Map.of("v", 2, "coins", -5, "level", 1,
                "bag", Map.of(), "discovered", Map.of()));
        assertThrows(BizException.class, () -> s.putCas(7L, bad, 1, SaveService.SOURCE_INTENT));
        verify(m, never()).casPut(anyLong(), anyString(), anyLong());
        verify(m, never()).insertFirst(anyLong(), anyString());
    }

    /* ---------------- 2. 历史抽样：谁记、谁不记 ---------------- */

    @Test
    void onlyIntentWritesAreSampledAndTheVeryFirstOneIsAlwaysKept() {
        SaveService s = svc(mock(SaveMapper.class), 25);
        assertTrue(s.historyWorth("admin", 2), "后台改档一条都不能漏：出事时要靠它追人");
        assertTrue(s.historyWorth("upload", 3), "客户端整档上传同理");
        assertTrue(s.historyWorth("rollback", 4));
        assertTrue(s.historyWorth("reset", 5), "重置前那一帧是玩家唯一能反悔的东西");
        assertTrue(s.historyWorth(SaveService.SOURCE_INTENT, 1), "初始帧必记：后台【回滚到最早一版】指的就是它");
        assertTrue(s.historyWorth(SaveService.SOURCE_INTENT, 25), "整点抽一条");
        assertFalse(s.historyWorth(SaveService.SOURCE_INTENT, 26), "中间那些重复帧不必逐条抄");
    }

    @Test
    void sampledOutWriteStillLandsInTheSaveRow() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.casPut(anyLong(), anyString(), anyLong())).thenReturn(1);

        svc(m, 25).putCas(7L, frame(777), 26, SaveService.SOURCE_INTENT);   // 第 27 版：没抽上

        verify(m).casPut(eq(7L), anyString(), eq(26L));
        verify(m, never()).addRevision(any());
    }

    @Test
    void aNonPositiveSampleIntervalDegradesToRecordingEverything() {
        // 运营把 history-every 配成 0 只想"先全都记着"，不该变成除零或者一条都不记
        SaveService s = svc(mock(SaveMapper.class), 0);
        assertTrue(s.historyWorth(SaveService.SOURCE_INTENT, 7));
        SaveService neg = svc(mock(SaveMapper.class), -3);
        assertTrue(neg.historyWorth(SaveService.SOURCE_INTENT, 7));
    }

    /**
     * 抽样之后，"最早那一版还在不在"完全由修剪那句 SQL 决定——这条不是形式检查。
     *
     * <p>e2e 里有一组"回滚到最早一版，金币退回初始档"的断言。历史改成每 25 版抽一条之后，
     * 修剪仍按版本号删掉 {@code MAX-30} 以前的行，于是账号一越过 31 版就把第 1 版删了：
     * 回滚退回到"中途某不知名的一版"，金币退回 5084 而不是 5000。红的那条断言说得对。
     * 抽样的本意是删重复帧，锚点不是重复帧。
     *
     * <p>为什么在这里读注解而不是 mock mapper 调用：这条约束住在 SQL 里，Java 层看不到它的执行；
     * 只有把句子本身钉住，以后谁把 {@code revision > 1} "顺手优化掉"才会当场红。
     */
    @Test
    void trimmingKeepsTheInitialFrameAsTheRollbackAnchor() throws Exception {
        String sql = String.join(" ", SaveMapper.class.getMethod("trimRevisionsBefore", long.class, long.class)
                .getAnnotation(org.apache.ibatis.annotations.Delete.class).value());
        assertTrue(sql.contains("revision > 1"),
                "修剪必须永远留下第 1 版，后台【回滚到最早一版】与玩家【历史】都指着它：" + sql);
        assertTrue(sql.contains("revision <= #{before}"),
                "窗口是递进来的号往前推，不再让 DELETE 在同表上求 MAX：" + sql);
        // 窗口本身住在这里，SQL 里只剩比较：谁改这个数都要在这里留一句理由。
        // 30 的来由是"抽样之后仍然看得见这段时间在干什么"，而不是"存多少行"。
        assertEquals(30, SaveService.KEEP_REVISIONS, "历史窗口仍是往前 30 个版本号");
    }

    /** 修剪的尺子从"我刚写到几号"量起，而不是让数据库自己回看历史表。 */
    @Test
    void trimmingMeasuresFromTheRevisionJustWritten() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.casPut(anyLong(), anyString(), anyLong())).thenReturn(1);

        svc(m, 25).putCas(7L, frame(900), 49, SaveService.SOURCE_INTENT);      // 写到第 50 版：抽得上

        verify(m).addRevision(argThat(x -> x.getRevision() == 50L));
        verify(m).trimRevisionsBefore(7L, 50L - SaveService.KEEP_REVISIONS);
    }

    /* ---------------- 3. 强类型写回：不再建那棵中间 JSON 树（G8） ---------------- */

    private static GameState state(long coins, int level) {
        GameState g = new GameState();
        g.coins = coins;
        g.level = level;
        return g;
    }

    /**
     * 服务端自己算坏的档也一样当场拒写。
     *
     * <p>{@code DbGameStore} 现在把 {@code GameState} 直接交给存档层，结构检查从"遍历一棵树"
     * 换成"对象自己答五条"（{@code GameState.defect()}）。这条钉的就是这次替换没把闸门换松：
     * 金币被扣成负数这种事一旦落库，下次载入就是坏档继续滚，比拒绝写回难查一个量级。
     */
    @Test
    void aTypedFrameThatBrokeItsOwnInvariantsNeverReachesTheDatabase() {
        SaveMapper m = mock(SaveMapper.class);
        SaveService s = svc(m, 25);

        assertThrows(BizException.class, () -> s.putCas(7L, state(-5, 1), 3, SaveService.SOURCE_INTENT));
        assertThrows(BizException.class, () -> s.putCas(7L, state(100, 0), 3, SaveService.SOURCE_INTENT));
        assertThrows(BizException.class, () -> s.putCas(7L, "not a save", 3, SaveService.SOURCE_INTENT));
        verify(m, never()).casPut(anyLong(), anyString(), anyLong());
        verify(m, never()).insertFirst(anyLong(), anyString());
    }

    /** 强类型那一帧落的盘，和 JsonNode 那条路落的是同一种东西：结构 + 版本位都在。 */
    @Test
    void typedFrameLandsAsTheSameJsonTheTreePathWouldHaveWritten() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.casPut(anyLong(), anyString(), anyLong())).thenReturn(1);
        GameState g = state(4321, 3);
        g.bag.put("Fe|1", 2);

        Map<String, Object> r = svc(m, 25).putCas(7L, g, 10, SaveService.SOURCE_INTENT);

        assertEquals(true, r.get("ok"));
        assertEquals(11L, r.get("revision"));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(m).casPut(eq(7L), json.capture(), eq(10L));
        assertTrue(json.getValue().contains("\"coins\":4321"), json.getValue());
        assertTrue(json.getValue().contains("\"sv\":" + GameState.SCHEMA_VERSION),
                "版本位由写盘那道门盖（不靠调用方各自记得 mark），别让它以 null 落库：" + json.getValue());
        assertEquals(GameState.SCHEMA_VERSION, g.sv == null ? -1 : g.sv.intValue(), "盖完就在对象上，序列化前后是同一个值");
    }

    /**
     * 帧上带着<b>旧</b>版本位时，下一次写盘仍要落回当前版本。
     *
     * <p>这条从 {@code SaveMigrationsTest} 搬过来：以前是 {@code DbGameStore} 在写前盖章，
     * 于是"漏盖"这件事只要新增一个写入方就可能发生（新调用方忘了 mark，库里就多一帧 {@code sv} 为 null
     * 的存档，下次读被当成早于版本位的老档走补历史那支）。现在章由写盘这道门统一盖，
     * 谁递进来都得被盖一次，包括内存里被改回旧值的那一帧（回滚、复制存档都会这么干）。
     */
    @Test
    void aFrameCarryingAnOlderSchemaBitIsRewrittenBeforeItLands() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.casPut(anyLong(), anyString(), anyLong())).thenReturn(1);
        GameState g = state(100, 1);
        g.sv = 1;                                       // 有人在内存里把它改回了旧值

        Map<String, Object> r = svc(m, 25).putCas(7L, g, 3, SaveService.SOURCE_INTENT);

        assertEquals(true, r.get("ok"));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(m).casPut(eq(7L), json.capture(), eq(3L));
        assertTrue(json.getValue().contains("\"sv\":" + GameState.SCHEMA_VERSION),
                "旧章必须被盖掉，否则这帧之后会被读成上一版结构：" + json.getValue());
    }

    /* ---------------- 4. 无条件写：号从写语句自己身上拿，不回表 ---------------- */

    /**
     * {@code force=true} 时既不需要基线（没人跟它抢），也不该回表读号。
     *
     * <p>这一条把 G8 那两处"同事务里查两遍"合成的那一句钉住：以前 upsert 前后各读一次
     * {@code currentRevision}，前者在无条件写下纯属白读，后者在并发下可能读到别人的号，
     * 于是历史表里会存进一个从没存在过的版本号。现在号来自 {@code lastRevision()}——
     * upsert 用 {@code LAST_INSERT_ID(revision+1)} 把它写在连接自己身上。
     */
    @Test
    void anUnconditionalWriteAsksForOneNumberAndTakesTheOneTheDatabaseJustMade() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.lastRevision()).thenReturn(78L);

        Map<String, Object> r = svc(m, 25).put(7L, state(1000, 2), null, true, "admin");

        assertEquals(true, r.get("ok"));
        assertEquals(78L, r.get("revision"), "落库的号要用数据库那一句自己算出来的，别自己 +1");
        verify(m, never()).currentRevision(anyLong());
        verify(m).upsert(eq(7L), anyString());
        // 非意图来源不抽样：后台改档那一条历史必须留下来，用来回答"这版金币是谁加的"
        verify(m).addRevision(argThat(x -> x.getRevision() == 78L && "admin".equals(x.getSource())));
    }

    /** 带基线的上传仍然要比对：省读不能省掉冲突检测，否则客户端整档上传会把别人的帧抹掉。 */
    @Test
    void aConditionalUploadStillComparesBeforeItOverwrites() {
        SaveMapper m = mock(SaveMapper.class);
        when(m.currentRevision(7L)).thenReturn(41L);

        Map<String, Object> r = svc(m, 25).put(7L, frame(1), 40L, false);

        assertEquals(true, r.get("conflict"), "号对不上就不能覆盖：" + r);
        assertEquals(41L, r.get("revision"));
        verify(m, never()).upsert(anyLong(), anyString());
    }
}
