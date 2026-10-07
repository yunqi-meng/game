package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权威服务 GameService 的端到端接线回归（无 DB）：载入 → 每日刷新 → 意图结算 → 写回 → {state, revision, events}。
 * 用内存 GameStore + 固定快照，覆盖 react/market/sign/挑战临时台桥接/沙盒/未知意图/会话隔离。
 */
class GameServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static volatile ContentRegistry.Snapshot SNAP;

    /**
     * 测试用的验签口令。F5 之后回调这一路**没有口令就一律拒**（演示模式也不例外，
     * 见 {@link AdService#callback} 的注释），所以本文件里"看完广告到账"必须走真签名，
     * 而不像以前那样靠 devMode 空跑——这正是想要的效果：意图测试演的就是线上那条路径。
     */
    private static final String AD_KEY = "game-svc-test-key";

    /** 同一份内容快照（读一次缓存起来）：本包其它回归也用它，免得每个类各读一遍 content-bundle.json。 */
    @SuppressWarnings("unchecked")
    static ContentRegistry.Snapshot snap() throws Exception {
        if (SNAP != null) return SNAP;
        Map<String, Object> bundle;
        try (InputStream in = GameServiceTest.class.getResourceAsStream("/content-bundle.json")) {
            assertNotNull(in);
            bundle = OM.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        long v = ((Number) bundle.getOrDefault("version", 0)).longValue();
        SNAP = new ContentRegistry(null, OM).build(v,
                (Map<String, Object>) bundle.getOrDefault("content", Map.of()),
                (Map<String, Object>) bundle.getOrDefault("config", Map.of()));
        return SNAP;
    }

    /**
     * 内存存档：单槽 + revision，语义对齐 DbGameStore。
     *
     * <p>读帧必带<b>副本</b>：不复制的话，所谓"两台设备各写各的"在测试里根本演不出来——
     * 两边改的是同一个 Java 对象，谁覆盖谁都看不出差别，CAS 写成一坨也能全绿。
     *
     * <p>写回同样存<b>副本</b>，且读写都加锁：真实落库是"序列化成一段 JSON 存进列"，
     * 之后玩家内存里那个对象怎么变都跟库里无关。这里要是存引用，并发用例就会拿
     * 一个正在被别人改的对象做 JSON 序列化，测出来的是 {@code ConcurrentModificationException}
     * 而不是我们要验的丢失更新。锁只加在"单条 SQL"的粒度上——
     * load→改→save 整段<b>故意</b>不原子，那正是 CAS 要收拾的窗口。
     */
    static final class MemStore implements GameStore {
        volatile GameState g; volatile long rev;
        /** 成功落盘次数：断言"只读意图一个字都不写"要看它。 */
        volatile int writes;
        /** 读帧之后立刻跑。回归用它钉住"我这帧还在结算，另一台设备已经写完落库"那一瞬。 */
        volatile Runnable afterLoad = () -> { };

        @Override
        public synchronized Optional<Frame> loadFrame(long uid) {
            Optional<Frame> f = Optional.ofNullable(g).map(x -> new Frame(copy(x), rev));
            afterLoad.run();
            return f;
        }

        @Override
        public synchronized long save(long uid, GameState s) { this.g = copy(s); writes++; return ++rev; }

        @Override
        public synchronized long saveCas(long uid, GameState s, long expected, String source) {
            if (expected != rev) return CONFLICT;      // 库里已经不是我读的那一帧：一个字都不写
            this.g = copy(s); this.rev = expected + 1; writes++;
            return rev;
        }

        /** 演"别的设备抢先写了一整帧"：换掉存的东西，并把号推上去。 */
        synchronized void otherDeviceWrites(GameState other) { this.g = copy(other); this.rev++; }

        synchronized GameState snapshot() { return g == null ? null : copy(g); }

        static GameState copy(GameState s) {
            try {
                return OM.readValue(OM.writeValueAsString(s), GameState.class);
            } catch (Exception e) {
                throw new IllegalStateException("GameState 应该能 JSON 往返", e);
            }
        }
    }

    private GameService svc() throws Exception {
        return svc(new IntentDedupe(OM));
    }

    /**
     * 接一个指定幂等窗口（F2）的装配。默认 {@link #svc()} 给每轮新建一份，
     * 而 F2 的用例要拿<b>同一个</b>窗口喂两次同 seq 的交付——"重发"这件事的全部意义就在跨请求复用。
     */
    private GameService svc(IntentDedupe dedupe) throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameEngine e = new GameEngine();
        MemAdTickets tickets = new MemAdTickets();
        // 演示模式开的是"本机自证"那条通道（ad.devGrant），callback 这一路从 F5 起一律要验签，
        // 所以这里就得带口令——空口令的 callback 现在连演示模式都不放过（见 AdService#callback 的注释）。
        // 换句话说：本文件里演的"看完广告到账"走的是线上同一条签名路径，不是一条后门。
        AdService ads = new AdService(tickets, AD_KEY, "{transId}{key}", "lower", true, "space-test");
        this.ads = ads; this.tickets = tickets;
        MemStore mem = new MemStore();
        this.store = mem;
        GameService svc = new GameService(() -> s, e, new EconomyService(e), mem, ads, uid -> { }, dedupe);
        svc.rng = () -> 0.999;
        return svc;
    }

    /** {@link #svc()} 刚建出来的那台内存存档：用例靠它直接摆前置条件、数写盘次数。 */
    private MemStore store;

    /** 被测服务用的广告侧搭档：意图测试要拿它模拟"广告网络回调"。 */
    AdService ads;
    MemAdTickets tickets;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> res(Map<String, Object> out) { return (Map<String, Object>) out.get("result"); }
    private static GameState state(Map<String, Object> out) { return (GameState) out.get("state"); }

    @Test
    void firstCallCreatesFreshStateAndPersists() throws Exception {
        GameService svc = svc();
        Map<String, Object> out = svc.state(1L);
        assertEquals(1L, ((Number) out.get("revision")).longValue(), "首帧落库 revision=1");
        GameState g = state(out);
        assertEquals(5000L, g.coins);
        assertTrue(g.level >= 1);
        assertNotNull(g.benchStates, "benchStates 已初始化");
    }

    @Test
    void reactIntentSynthesizesAndPersistsToBag() throws Exception {
        GameService svc = svc();
        svc.state(1L);   // 建号
        assertTrue(res(svc.act(1L, "bench.temp", Map.of("temp", "ignite"))).get("ok") != null);
        assertEquals(true, res(svc.act(1L, "bench.place", Map.of("id", "H2", "n", 2))).get("ok"));
        assertEquals(true, res(svc.act(1L, "bench.place", Map.of("id", "O2", "n", 1))).get("ok"));
        Map<String, Object> out = svc.act(1L, "react", Map.of());
        Map<String, Object> r = res(out);
        String kind = (String) r.get("kind");
        assertTrue("success".equals(kind) || "partial".equals(kind), "kind=" + kind);
        Map<String, Integer> produced = (Map<String, Integer>) r.get("produced");
        assertTrue(produced.getOrDefault("H2O", 0) >= 1, "生成水");
        // 回执 events 首条带 type + 结算字段
        assertEquals("react", ((java.util.List<Map<String, Object>>) out.get("events")).get(0).get("type"));
        // 已写回背包
        assertTrue(state(out).discovered.containsKey("H2O"), "发现水并持久化");
    }

    @Test
    void sellIntentAddsCoins() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        long before = state(last(svc, 1L)).coins;
        Map<String, Object> out = svc.act(1L, "market.sell", Map.of("id", "Na", "q", 0, "n", 2));
        assertEquals(true, res(out).get("ok"), "卖出 Na");
        assertTrue(state(out).coins > before, "金币增加");
    }

    @Test
    void signIntentRewardsOncePerDay() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        Map<String, Object> r1 = res(svc.act(1L, "sign", Map.of()));
        assertEquals(true, r1.get("ok"));
        assertEquals(1, ((Number) r1.get("day")).intValue());
        Map<String, Object> r2 = res(svc.act(1L, "sign", Map.of()));
        assertEquals(false, r2.get("ok"), "同日重复签到被拒");
    }

    @Test
    void challengeTempBenchBridgesAcrossRequests() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        Map<String, Object> start = svc.act(1L, "challenge.start", Map.of());
        GameState g = state(start);
        assertNotNull(g.chal, "已生成挑战");
        assertEquals(true, res(start).get("ok"));
        String mat = g.chal.given.keySet().iterator().next();
        Map<String, Object> place = svc.act(1L, "bench.place", Map.of("id", mat, "n", 1));
        assertEquals(true, res(place).get("ok"), "材料箱内可投放");
        assertNotNull(g.chal.bench, "临时台回填进存档 (chal.bench)");
        assertTrue(g.chal.bench.placed.getOrDefault(mat, 0) >= 1, "投放计入挑战现场");
        // 退出清理现场
        svc.act(1L, "challenge.quit", Map.of());
        assertNull(state(last(svc, 1L)).chal, "退出后 chal 清空");
    }

    @Test
    void sandboxPlaceDoesNotTouchBag() throws Exception {
        GameService svc = svc();
        Map<String, Object> enter = svc.act(1L, "sandbox.enter", Map.of());
        assertEquals(Boolean.TRUE, enter.get("sandbox"), "回执标记沙盒模式");
        assertNotNull(enter.get("bench"), "沙盒回传临时台供渲染");
        Map<String, Object> place = svc.act(1L, "bench.place", Map.of("id", "Au", "n", 3));
        assertEquals(true, res(place).get("ok"), "沙盒自由投放（无需库存）");
        GameState g = state(last(svc, 1L));
        int au = 0;
        for (String k : g.bag.keySet()) if (k.startsWith("Au|")) au += g.bag.get(k);
        assertEquals(0, au, "沙盒不写入背包");
    }

    @Test
    void unknownIntentRejectedAndSessionsIsolated() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        Map<String, Object> bad = res(svc.act(1L, "does.not.exist", Map.of()));
        assertEquals(false, bad.get("ok"), "未知意图返回失败");
        // EngineCtx 按 uid 隔离：uid1 进入沙盒不应让 uid2 的投放免库存
        svc.act(1L, "sandbox.enter", Map.of());
        Map<String, Object> u2 = res(svc.act(2L, "bench.place", Map.of("id", "Au", "n", 3)));
        assertEquals(false, u2.get("ok"), "uid2 非沙盒：无 Au 库存应被拒");
    }

    @Test
    void settingsTutorialAndAdBonusAreServerSide() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        Map<String, Object> set = svc.act(1L, "settings", Map.of(
                "realMode", true, "volSfx", 42, "music", true, "tutorial", 2, "bogus", 999999));
        assertEquals(true, res(set).get("ok"));
        GameState g = state(set);
        assertTrue(g.realMode);
        assertEquals(42, g.volSfx);
        assertTrue(g.music);
        assertEquals(2, g.tutorial);
        assertEquals(5000L, g.coins, "settings 不接受白名单外的键，金币未被篡改");

        Map<String, Object> tut = res(svc.act(1L, "tutorial.step", Map.of("to", 3)));
        assertEquals(true, tut.get("ok"));
        assertEquals(0L, ((Number) tut.get("bonus")).longValue(), "非 3→4 不发奖金");
        Map<String, Object> tut2 = res(svc.act(1L, "tutorial.step", Map.of("to", 4)));
        assertTrue(((Number) tut2.get("bonus")).longValue() > 0, "完成首次实验发放启动资金");
        GameState g2 = state(svc.state(1L));
        assertEquals(4, g2.tutorial);
        assertTrue(g2.coins > 5000L);
        long after = g2.coins;
        svc.act(1L, "tutorial.step", Map.of("to", 5));
        assertEquals(after, state(last(svc, 1L)).coins, "重复推进不再发钱");

        Map<String, Object> ad = res(svc.act(1L, "ad.bonus", Map.of("kind", 0)));
        assertEquals(false, ad.get("ok"), "自证看广告已废除：客户端说看过就发钱等于无限提款机");
        assertNotNull(ad.get("msg"), "回绝要给出指路文案");
        Map<String, Object> req = res(svc.act(1L, "ad.request", Map.of("kind", "boom")));
        assertEquals(true, req.get("ok"), "改为签发工单");
        assertNotNull(req.get("ticket"), "工单号即 SDK extra");
        assertEquals(500, ((Number) req.get("amount")).intValue(), "慰问金数额由服务端定格");
        assertEquals(false, res(svc.act(1L, "ad.request", Map.of("kind", "boom"))).get("ok"), "同位一次只挂一张未完成工单");
    }

    @Test
    void reactCarriesMultiplierAndPersistsInsurance() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        svc.act(1L, "settings", Map.of("insured", true));
        assertTrue(state(last(svc, 1L)).insured, "保险偏好入库");
        svc.act(1L, "bench.temp", Map.of("temp", "ignite"));
        svc.act(1L, "bench.place", Map.of("id", "H2", "n", 2));
        svc.act(1L, "bench.place", Map.of("id", "O2", "n", 1));
        Map<String, Object> r = res(svc.act(1L, "react", Map.of("multiplier", 1)));
        assertNotNull(r.get("rid"), "回执带反应 id 供客户端查表渲染");
        assertTrue(state(svc.state(1L)).insured, "本次未出事故：保险仍在（由 react 参数消耗）");
    }

    @Test
    void tempAndElectrolysisRequireTheirEquipment() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        // 新手只带酒精灯：heat/ignite 可用，高温与电解必须有对应设备
        assertEquals(true, res(svc.act(1L, "bench.temp", Map.of("temp", "heat"))).get("ok"));
        assertEquals(false, res(svc.act(1L, "bench.temp", Map.of("temp", "highTemp"))).get("ok"), "无喷灯不得设高温");
        assertEquals(false, res(svc.act(1L, "bench.electrolysis", Map.of("on", true))).get("ok"), "无电解器不得开电解");
        // 前置条件要摆进<b>存档</b>，不是摆进上一帧返回的那个副本：读帧带副本之后，
        // 改副本不会再影响下一次意图（真实 store 本来就是这个语义，以前那条用例是侥幸过的）
        store.g.equipment.put("blowtorch", true);
        store.g.equipment.put("electrolyzer", true);
        assertEquals(true, res(svc.act(1L, "bench.temp", Map.of("temp", "highTemp"))).get("ok"));
        assertEquals(true, res(svc.act(1L, "bench.electrolysis", Map.of("on", true))).get("ok"));
    }

    @Test
    void doubleClaimNeedsTheAdCoupon() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        assertEquals(false, res(svc.act(1L, "claim.daily", Map.of("id", "any", "dbl", true))).get("ok"));
        // 券只能靠看完一段激励视频换到：签发 → 广告网络回调 → 下一次取帧结算
        String ticket = String.valueOf(res(svc.act(1L, "ad.request", Map.of("kind", "dbl"))).get("ticket"));
        // F5 之后 callback 这一路没有签名就是不走，演示模式也照样拒（先演这条，免得日后被人"顺手放宽"）
        assertEquals(false, ads.callback(Map.of("trans_id", "tx-dbl-0", "extra", ticket, "user_id", "1")).get("ok"),
                "不带签名的回调必须拒");
        assertEquals(true, ads.callback(Map.of("trans_id", "tx-dbl-1", "extra", ticket, "user_id", "1",
                "sign", sha256Hex("tx-dbl-1" + AD_KEY))).get("ok"), "回调验签通过");
        Map<String, Object> frame = svc.state(1L);
        assertEquals(true, state(frame).daily.claimed.get("__dblCoupon"), "双倍券随结算入账");
        java.util.List<Map<String, Object>> evs = (java.util.List<Map<String, Object>>) frame.get("events");
        assertEquals("ad", evs.get(0).get("type"), "到账用 type=ad 事件回传，排在最前");
        assertEquals("dbl", ((Map<String, Object>) ((java.util.List<?>) evs.get(0).get("granted")).get(0)).get("kind"));
        assertEquals(false, res(svc.act(1L, "ad.request", Map.of("kind", "dbl"))).get("ok"), "当日只有一张");
    }

    /* ================= F1：带号写回（丢帧、只读不落盘、奖励不随作废帧陪葬） ================= */

    @Test
    void readOnlyIntentsDoNotTouchTheSaveAtAll() throws Exception {
        GameService svc = svc();
        Map<String, Object> first = svc.state(1L);
        long rev0 = revOf(first);
        int writes0 = store.writes;
        assertTrue(writes0 > 0, "首帧必须落盘：存档行还没有，玩家下次刷新得拿到同一份东西");

        for (String intent : new String[]{"state", "ad.status", "leaderboard", "quiz.pickOne"}) {
            Map<String, Object> f = svc.act(1L, intent, Map.of("grade", "all"));
            assertNotNull(f.get("state"), intent + " 照样回整帧，客户端渲染路径不用分叉");
            assertEquals(rev0, revOf(f), intent + " 是只读意图，不许推高 revision");
        }
        assertEquals(writes0, store.writes, "这四条意图一次盘都不该落：以前每次取帧都白抄一遍全量存档");
    }

    @Test
    void aFrameWrittenElsewhereIsReplayedRatherThanOverwritten() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        long coins0 = state(last(svc, 1L)).coins;
        // 另一台设备（平板）在我们结算这一帧的工夫里整帧写回了 +12345 金币：
        // 抢输的一方必须重读重放，而不是把对方那一帧连金币一起抹掉。
        boolean[] fired = {false};
        store.afterLoad = () -> {
            if (fired[0]) return;
            fired[0] = true;
            GameState other = MemStore.copy(store.g);
            other.coins += 12_345;
            store.otherDeviceWrites(other);
        };
        assertEquals(true, res(svc.act(1L, "settings", Map.of("music", true))).get("ok"));
        GameState g = state(last(svc, 1L));
        assertEquals(coins0 + 12_345, g.coins, "别人写的金币一分别少");
        assertTrue(g.music, "我这帧的改动也在：重放不是放弃");
    }

    @Test
    void aFrameThatNeverWinsSaysSoInsteadOfPretending() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        long coins0 = state(last(svc, 1L)).coins;
        int[] hookRuns = {0};
        store.afterLoad = () -> {                       // 每一次读帧后都有人抢先落盘
            hookRuns[0]++;
            GameState other = MemStore.copy(store.g);
            other.coins += 1;
            store.otherDeviceWrites(other);
        };
        Map<String, Object> f = svc.act(1L, "settings", Map.of("music", true));
        Map<String, Object> r = res(f);
        assertEquals(false, r.get("ok"), "写不进去就不能装作成功");
        assertEquals(true, r.get("stale"), "回执要明说是撞号，而不是" + "未知错误");
        assertEquals(true, f.get("stale"), "整帧也带 stale 标记，客户端才知道要重取");
        assertFalse(state(f).music, "回给客户端的是库里那一帧，不是作废的内存副本");
        assertTrue(state(f).coins > coins0, "回给客户端的帧里只有别人写进去的增量，没有我们没落盘的那份");
        assertTrue(hookRuns[0] <= GameService.CAS_RETRIES + 2,
                "重放必须有界，不能自旋：" + hookRuns[0]);
    }

    @Test
    void adRewardSurvivesTheFrameItLandedOn() throws Exception {
        GameService svc = svc();
        long coins0 = state(svc.state(1L)).coins;
        String ticket = String.valueOf(res(svc.act(1L, "ad.request", Map.of("kind", "boom"))).get("ticket"));
        String trans = "tx-boom-" + ticket;
        assertEquals(true, ads.callback(Map.of("trans_id", trans, "extra", ticket, "user_id", "1",
                "sign", sha256Hex(trans + AD_KEY))).get("ok"), "回调验签通过");
        boolean[] fired = {false};
        store.afterLoad = () -> {
            if (fired[0]) return;
            fired[0] = true;
            GameState other = MemStore.copy(store.g);
            other.diamonds += 7;
            store.otherDeviceWrites(other);
        };
        Map<String, Object> f = svc.state(1L);          // 这一帧负责结算：先撞上别的设备，再重放
        GameState g = state(f);
        assertEquals(7L, g.diamonds, "别人写的钻石在");
        assertTrue(g.coins > coins0, "广告奖励不能跟着作废的那一帧一起没了");
        assertEquals(1, adGrants(f).size(), "重放之后到账明细照样回传给客户端");
        assertEquals(2, tickets.settleClaims, "抢到两次结算权：第一次那一帧作废了");
        assertEquals(1, tickets.requeueClaims, "作废的那次必须退回待结算，才会有第二次");
    }

    /**
     * 升级方案里点名要的那条并发回归："两个 act() 并发，断言金币与积分之和不多不少"。
     * 这里放大到 6 个真线程同时买同一种耗材，断言的是<b>账本守恒</b>：
     * 金币只按成功回执里的价款扣，背包只按成功次数发货，
     * 而每一笔必须有下落——成交、或明说撞号（{@code stale}）让玩家重试，不许静默消失。
     *
     * <p>为什么敢用真线程：F1 之前这里必然对不上账——后写的整帧把先写的覆盖掉，
     * 玩家付了两次钱只买到一次货。有了带 revision 的写回，抢输的一方只会重读重放，
     * 于是"成功次数 × 单价 == 扣掉的金币"这条恒等式在任意交错下都成立。
     */
    @Test
    void concurrentIntentsCostExactlyWhatTheyReported() throws Exception {
        GameService svc = svc();
        long uid = 9L;
        long coins0 = state(svc.state(uid)).coins;

        int players = 6;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong spent = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger stale = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> refused = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        Thread[] t = new Thread[players];
        for (int i = 0; i < players; i++) {
            t[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Map<String, Object> f = svc.act(uid, "market.consumable", Map.of("id", "filterpaper", "n", 1));
                Map<String, Object> r = res(f);
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    ok.incrementAndGet();
                    spent.addAndGet(((Number) r.getOrDefault("cost", 0L)).longValue());
                } else if (Boolean.TRUE.equals(f.get("stale"))) {
                    stale.incrementAndGet();
                } else {
                    refused.add(String.valueOf(r.get("msg")));
                }
            }, "device-" + i);
            t[i].start();
        }
        go.countDown();
        for (Thread th : t) th.join(30_000);

        assertEquals(players, ok.get() + stale.get() + refused.size(),
                "每笔要么成交、要么明说撞号，不许静默吞掉：被拒的=" + refused);
        assertTrue(ok.get() >= 1, "至少一笔该成功：" + refused);
        assertTrue(refused.isEmpty(), "并发下不该出现第三种失败：" + refused);

        GameState g = store.snapshot();
        assertEquals(coins0 - spent.get(), g.coins, "金币只按成功回执扣：不多不少");
        assertEquals(ok.get(), (int) g.bag.getOrDefault("filterpaper|0", 0), "货只按成功次数发：不多不少");
    }

    /* ================= F2：意图幂等（同一句被交付两次，只结算一次） ================= */

    /**
     * 弱网重发的正身：同一 {@code (uid, sid, seq)} 第二次到来，服务端一个字都不写、一次都不结算，
     * 只是把第一次算出来的那一帧原样退回。
     *
     * <p>"逐字相同"是这条用例最硬的部分：只有两次返回的是同一份 JSON，客户端才不可能因为
     * 第二次的回执把界面画回旧值。缓存里存的是序列化后的字符串（见 {@link IntentDedupe} 类注释），
     * 退回的是解析回来的 Map，再序列化一遍仍然等于第一次那一帧——这正是要钉住的口径。
     */
    @Test
    void duplicateSeqReturnsTheFirstFrameInsteadOfBuyingAgain() throws Exception {
        GameService svc = svc();
        long uid = 1L, sid = 77L;
        Map<String, Object> p = Map.of("id", "filterpaper", "n", 1, "seq", 41);
        long coins0 = state(svc.state(uid)).coins;

        Map<String, Object> a = svc.act(uid, sid, "market.consumable", p);
        int writes = store.writes;
        long cost = ((Number) res(a).getOrDefault("cost", 0L)).longValue();
        Map<String, Object> b = svc.act(uid, sid, "market.consumable", p);   // 链路把同一句又送来一次

        assertEquals(true, res(a).get("ok"), "第一次该正常成交");
        assertEquals(true, res(b).get("ok"), "重复交付不能回`失败`：那一次确实成功过，玩家的钱已经扣了");
        assertEquals(writes, store.writes, "第二次一个字都不该写盘：它只是把第一次那一帧退回");

        GameState g = state(svc.state(uid));
        assertEquals(1, g.bag.getOrDefault("filterpaper|0", 0), "两次交付只到一件货");
        assertEquals(coins0 - cost, g.coins, "两次交付只扣一笔钱（成本：" + cost + "）");
        assertEquals(OM.writeValueAsString(a), OM.writeValueAsString(b),
                "两次返回必须逐字节相同，否则界面会被重发的那一次画回旧值");
    }

    /**
     * 换号就是换意图：连点两次买两件事是玩家的意志（F3 只拦同一个人手抖），
     * 而"同一个 seq 再来一次"才是网络的重复交付。这条与上一条配对，才是幂等键的完整语义。
     */
    @Test
    void differentSeqsAreTwoRealPurchases() throws Exception {
        GameService svc = svc();
        long uid = 1L, sid = 77L;
        long coins0 = state(svc.state(uid)).coins;
        Map<String, Object> a = svc.act(uid, sid, "market.consumable", Map.of("id", "filterpaper", "n", 1, "seq", 41));
        Map<String, Object> b = svc.act(uid, sid, "market.consumable", Map.of("id", "filterpaper", "n", 1, "seq", 42));
        long cost = ((Number) res(a).getOrDefault("cost", 0L)).longValue()
                + ((Number) res(b).getOrDefault("cost", 0L)).longValue();
        GameState g = state(svc.state(uid));
        assertEquals(2, g.bag.getOrDefault("filterpaper|0", 0), "两个 seq 就是两笔生意");
        assertEquals(coins0 - cost, g.coins);
    }

    /**
     * 旧安装包不发 {@code seq}（还有玩家没更新），必须退回 F2 之前的行为：<b>不做任何猜测式去重</b>。
     * 这条同时也是"服务端不按参数内容去重"的服务层版本，和 e2e 那条"连发两次真的执行两次"同源。
     */
    @Test
    void aPackageWithoutSeqIsUnchangedBehaviour() throws Exception {
        GameService svc = svc();
        long uid = 1L, sid = 501L;
        state(svc.state(uid));
        assertEquals(true, res(svc.act(uid, sid, "market.consumable", Map.of("id", "filterpaper", "n", 1))).get("ok"));
        assertEquals(true, res(svc.act(uid, sid, "market.consumable", Map.of("id", "filterpaper", "n", 1))).get("ok"));
        assertEquals(2, state(svc.state(uid)).bag.getOrDefault("filterpaper|0", 0),
                "没有序号就没有幂等键：两笔都得执行，宁可让重发多买一次，也不许把玩家的第二次点击吞掉");
    }

    /**
     * 幂等键必须带 {@code sid}：客户端的 {@code seq} 是<b>会话内</b>自增的，玩家退出重登后又从 1 数起。
     * 若只按 uid 认，重登后的第一笔会用 1 号槽位命中上一次会话留下的那一帧——
     * 症状是"点了购买，金币没少、货也没到，还回了个 ok"，比重复扣款难查一个数量级。
     */
    @Test
    void aNewSessionMayReuseSequenceNumberOne() throws Exception {
        IntentDedupe d = new IntentDedupe(OM);
        GameService svc = svc(d);
        long uid = 1L;
        state(svc.state(uid));
        Map<String, Object> p = Map.of("id", "filterpaper", "n", 1, "seq", 1);
        assertEquals(true, res(svc.act(uid, 100L, "market.consumable", p)).get("ok"), "上一次会话的第 1 号");
        assertEquals(true, res(svc.act(uid, 200L, "market.consumable", p)).get("ok"), "重登之后的第 1 号是另一笔生意");
        assertEquals(2, state(svc.state(uid)).bag.getOrDefault("filterpaper|0", 0),
                "换了 sid 就不能拿旧会话的回执糊弄玩家");
    }

    /**
     * 只读意图不进幂等窗口：客户端每 8～30 秒拉一次 {@code state}，把它们缓存起来只会把
     * 真正需要去重的写意图挤出窗口（配额见 {@link IntentDedupe}），而读本来就没有副作用可去重。
     */
    @Test
    void readOnlyIntentsNeverOccupyTheDedupeWindow() throws Exception {
        IntentDedupe d = new IntentDedupe(OM);
        GameService svc = svc(d);
        long uid = 1L;
        svc.act(uid, 9L, "state", Map.of("seq", 1));
        svc.act(uid, 9L, "ad.status", Map.of("seq", 2));
        svc.act(uid, 9L, "leaderboard", Map.of("seq", 3));
        assertEquals(0, d.cachedSlots(uid), "三条只读一条都不该留在窗口里");
        svc.act(uid, 9L, "market.consumable", Map.of("id", "filterpaper", "n", 1, "seq", 4));
        assertEquals(1, d.cachedSlots(uid), "写意图才占槽位");
    }

    /**
     * {@code stale} 那一帧什么都没写进库，所以它<b>不该</b>进缓存：
     * 否则玩家重发同一句意图时，会永远收到同一句"请重试"，那句购买再也做不成。
     * 判据用"读了几次帧"：第二次交付必须又走一遍完整的载入→结算。
     */
    @Test
    void aStaleFrameIsNotCachedSoTheResendGetsARealTry() throws Exception {
        IntentDedupe d = new IntentDedupe(OM);
        GameService svc = svc(d);
        long uid = 1L, sid = 9L;
        state(svc.state(uid));
        int[] hookRuns = {0};
        store.afterLoad = () -> {                        // 每次读帧都有人抢先落盘：这一句永远写不进去
            hookRuns[0]++;
            GameState other = MemStore.copy(store.g);
            other.coins += 1;
            store.otherDeviceWrites(other);
        };
        Map<String, Object> p = Map.of("music", true, "seq", 33);
        Map<String, Object> a = svc.act(uid, sid, "settings", p);
        assertEquals(true, a.get("stale"), "前置条件：这一帧确实抢不过");
        int afterFirst = hookRuns[0];
        Map<String, Object> b = svc.act(uid, sid, "settings", p);
        assertEquals(true, b.get("stale"), "重复交付不能因为缓存了 stale 就永远回`请重试`");
        assertTrue(hookRuns[0] > afterFirst,
                "第二次必须真的重新结算过（第一次读帧 " + afterFirst + " 次，现在 " + hookRuns[0] + " 次）");
    }

    /**
     * G7 尾巴之外顺手钉住的一条帧自洽性：买房间那一笔意图里 {@code rooms} 才 +1，而
     * {@code ensureBenches} 以前只在<b>进帧</b>时跑，于是下发的那一帧带着 rooms=2、benchStates=1
     * 出门——客户端要多点一次意图（或切一次台）才看见第二张台。这里把"出门即自洽"钉死，
     * 并且连<b>落库</b>的那一帧一起验：不能只是回执里好看、下次刷新又缩回去。
     */
    @Test
    void theFrameThatBuysARoomAlreadyCarriesTheNewBench() throws Exception {
        GameService svc = svc();
        Map<String, Object> fresh = svc.state(1L);       // 建号
        int rooms0 = state(fresh).rooms.size();
        assertEquals(rooms0, state(fresh).benchStates.size(), "前置条件：买之前台数就跟着房间数");

        store.g.level = 16;                              // 房间有等级线（analysis Lv.12 / 6 万金币），
        store.g.coins = 200000L;                         // 用真实门槛当前置，不去伪造余额或绕开关
        Map<String, Object> out = svc.act(1L, "upgrade.room", Map.of("id", "analysis"));
        assertEquals(true, res(out).get("ok"), "买得通分析化学室：" + res(out));

        GameState g = state(out);
        assertEquals(rooms0 + 1, g.rooms.size(), "rooms +1");
        assertEquals(g.rooms.size(), g.benchStates.size(),
                "同一帧里 benchStates 就得跟着长：客户端不该多点一次才拿到第二张台");
        assertEquals(g.rooms.size(), store.snapshot().benchStates.size(),
                "落库的那一帧也是撑开的，不是只在回执里自洽");
    }

    /** 从整帧的事件流里取出广告到账明细（type=ad 那条）。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<Object> adGrants(Map<String, Object> frame) {
        for (Map<String, Object> e : (java.util.List<Map<String, Object>>) frame.get("events"))
            if ("ad".equals(e.get("type"))) return (java.util.List<Object>) e.get("granted");
        return java.util.List.of();
    }

    private static long revOf(Map<String, Object> frame) {
        Object v = frame.get("revision");
        return v instanceof Number n ? n.longValue() : -1;
    }

    private static Map<String, Object> last(GameService svc, long uid) { return svc.state(uid); }

    /** 与 {@code AdServiceTest#sign} 同一算法：模板 {@code {transId}{key}} 的 SHA-256 小写十六进制。 */
    private static String sha256Hex(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
