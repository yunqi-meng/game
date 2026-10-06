package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 共享黄金向量表的服务端半边（H1）：读 {@code test/golden/vectors.json}，按每条向量摆好存档与台面，
 * 跑 {@link GameEngine} 的预测电路（matches / pick / rollDanger / batchCap / maxMultiplier）与经济读数
 * （repTier / buyPrice / sellPrice / discoverBonus），再跑真正的 {@link GameEngine#react} 结算，
 * 把两批读数逐条钉回向量表。
 *
 * <p>为什么值得单独一层：客户端不再写存档，但它<b>预测</b>的东西是玩家看得见的——台面上走哪条方程式、
 * 原料值多少、批量能到几倍、有没有危险。这些在 {@code js/engine.js} 里有一份实现，在这里有一份权威实现。
 * 两边漂移的后果不是崩溃，是"界面写着 A 式、落账是 B 式"和"预览报价是假的"，
 * 而这类问题只有同时改两端数值才会露出来，人工回归扫不到。
 *
 * <p>字段归属写死在 {@code test/golden/run.js}：共判字段（上面那批）两端各算各的再比对；
 * {@code expect.cost} 只有客户端算（服务端没有成本预览这条路）；{@code expect.react} 只有服务端算
 * （客户端不结算）。任何一侧少钉一条，第 1 层的 auditCoverage 就会红——包括有人把这个文件删掉或
 * 改成不读 vectors.json 的情况。
 *
 * <p>确定性靠两件事：注入常量 {@code rng}（{@code GameEngine} 全程只经 rng 取随机），
 * 以及把 {@code market.date} 填成向量里的日期、{@code market.drift} 填成向量里那份漂移，
 * 这样 {@link EconomyService} 不会当场重算每日行情（那会把价格变成一个每天不同的数）。
 * 夹具 {@code content-bundle.json} 与 {@code frontend/js/data/*.js} 的逐条对齐由第 1 层守着，
 * 所以这里可以假定"两端读的是同一份内容表"。
 *
 * <p>重新对表（有意改数值之后）：{@code mvn -o test -Dtest=GoldenVectorsTest -Dgolden.dump=true}
 * 会把服务端结算写进 {@code test/golden/java-dump.json}，再 {@code node test/golden/run.js --merge} 并进
 * {@code expect.react}；日常跑（含 {@code test/ci.sh}）永远走断言分支，不看 dump。
 */
class GoldenVectorsTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** dump 模式下攒下来的服务端结算，跑完一次写盘。 */
    private static final Map<String, Object> DUMPED = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 找向量表：surefire 的 cwd 是 {@code server/}，而 IDE 直接跑时常常是仓库根。
     * 找不到就抛——这条回归"没东西可跑"必须是红的，不能静默通过（第 1 层还会查这个文件在不在）。
     */
    private static Path vectorsFile() {
        String override = System.getProperty("golden.dir");
        List<Path> cand = new ArrayList<>();
        if (override != null) cand.add(Paths.get(override, "vectors.json"));
        cand.add(Paths.get("..", "test", "golden", "vectors.json"));
        cand.add(Paths.get("test", "golden", "vectors.json"));
        cand.add(Paths.get("..", "..", "test", "golden", "vectors.json"));
        for (Path p : cand) {
            if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("找不到 test/golden/vectors.json（试过 " + cand + "）");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> table() throws Exception {
        Path p = vectorsFile();
        return OM.readValue(Files.readAllBytes(p), new TypeReference<List<Map<String, Object>>>() {
        });
    }

    static Stream<? extends Arguments> vectors() throws Exception {
        List<Map<String, Object>> t = table();
        assertTrue(t.size() >= 10, "向量表少于 10 条就等于放弃了这层回归，当前 " + t.size() + " 条");
        List<Arguments> out = new ArrayList<>();
        for (Map<String, Object> v : t) out.add(Arguments.of(String.valueOf(v.get("id")), v));
        return out.stream();
    }

    /* ================= 每条向量 ================= */
    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void goldenVector(String id, Map<String, Object> v) throws Exception {
        ContentRegistry.Snapshot s = Snapshots.base();
        GameEngine e = new GameEngine();
        EconomyService eco = new EconomyService(e);

        long contentVersion = ((Number) v.getOrDefault("contentVersion", 0)).longValue();
        assertEquals(s.version, contentVersion,
                "向量 " + id + " 的 contentVersion 与夹具 bundle 不符：内容表换版了，这张表要重新对表");

        Map<String, Object> seed = map(v.get("seed"));
        Map<String, Object> mode = map(v.get("mode"));
        Map<String, Object> bm = map(v.get("bench"));
        long now = Instant.parse(str(seed.get("marketDate")) + "T12:00:00Z").toEpochMilli();
        double rngVal = num(v.get("rng"), 0.5);
        DoubleSupplier rng = () -> rngVal;

        GameState g = state(seed, bm);
        EngineCtx ctx = new EngineCtx();
        ctx.multiplier = (int) num(mode.get("multiplier"), 1);
        ctx.insured = bool(mode.get("insured"));
        if (bool(mode.get("sandbox"))) {
            // 与 GameService 的 sandbox.enter 同构：会话级临时台，内容与向量一致（客户端那边拿 benches[0] 摆同一张台）
            ctx.tempBench = bench(bm);
            ctx.sandboxActive = true;
        }

        Map<String, Object> expect = map(v.get("expect"));
        assertNotNull(expect, "向量 " + id + " 没有 expect：第 1 层的 --dump 还没跑过？");

        /* ---------- 共判字段：两端各算各的 ---------- */
        GameState.Bench b = e.cur(g, ctx);
        List<GameEngine.Candidate> rs = e.matches(g, s, ctx);
        GameEngine.Candidate pick = e.pick(rs, b.placed);
        List<String> matchIds = new ArrayList<>();
        for (GameEngine.Candidate c : rs) matchIds.add(c.id());
        Content.DangerDef danger = e.rollDanger(s, b.placed);

        assertSame2(id + ".matchIds", expect.get("matchIds"), matchIds);
        assertSame2(id + ".pickId", expect.get("pickId"), pick == null ? null : pick.id());
        assertSame2(id + ".eq", expect.get("eq"), pick == null ? null : pick.eq());
        assertSame2(id + ".fx", expect.get("fx"), pick == null ? List.of() : pick.fx());
        assertSame2(id + ".danger", expect.get("danger"), danger != null);
        assertSame2(id + ".dangerId", expect.get("dangerId"), danger == null ? null : danger.id());
        assertSame2(id + ".batchCap", expect.get("batchCap"), e.batchCap(g, s, ctx));
        assertSame2(id + ".maxMultiplier", expect.get("maxMultiplier"),
                pick == null ? null : e.maxMultiplier(g, s, ctx, pick));

        assertSame2(id + ".repTier", expect.get("repTier"), repView(g, s, eco));

        List<Object> wantPrices = list(expect.get("prices"));
        List<Object> probePrices = list(map(v.get("probe")).get("prices"));
        assertEquals(wantPrices.size(), probePrices.size(), id + "：prices 探针与期望条数不一致");
        for (int i = 0; i < probePrices.size(); i++) {
            Map<String, Object> pr = map(probePrices.get(i));
            String pid = str(pr.get("id"));
            int q = (int) num(pr.get("q"), 0);
            Map<String, Object> got = new LinkedHashMap<>();
            got.put("id", pid); got.put("q", q);
            got.put("buy", eco.buyPrice(g, s, pid, now));
            got.put("sell", eco.sellPrice(g, s, pid, q, now));
            assertPrice(id, i, map(wantPrices.get(i)), got);
        }

        List<Object> wantBonus = list(expect.get("bonus"));
        List<Object> probeBonus = list(map(v.get("probe")).get("bonus"));
        assertEquals(wantBonus.size(), probeBonus.size(), id + "：bonus 探针与期望条数不一致");
        for (int i = 0; i < probeBonus.size(); i++) {
            String bid = str(map(probeBonus.get(i)).get("id"));
            Map<String, Object> got = new LinkedHashMap<>();
            got.put("id", bid);
            got.put("plain", e.discoverBonus(g, s, bid, false));
            got.put("forReaction", e.discoverBonus(g, s, bid, true));
            assertPrice(id + ".bonus", i, map(wantBonus.get(i)), got);
        }

        /* ---------- 结算：只有服务端算得出，钉的是 expect.react ---------- */
        long coinsBefore = g.coins;
        Map<String, Integer> bagBefore = new LinkedHashMap<>(g.bag);
        List<String> discBefore = new ArrayList<>(g.discovered.keySet());

        GameEngine.Result res = e.react(g, s, ctx, rng);
        Map<String, Object> got = reactView(g, res, coinsBefore, bagBefore, discBefore);

        if (System.getProperty("golden.dump") != null) {
            DUMPED.put(id, got);
            return;
        }
        Object want = expect.get("react");
        assertNotNull(want, "向量 " + id + " 没钉 expect.react：跑 "
                + "mvn -o test -Dtest=GoldenVectorsTest -Dgolden.dump=true 后 node test/golden/run.js --merge");
        assertSame2(id + ".react", want, got);
    }

    /** 结算的可比对视图：只收"玩家会察觉"的量，不收文案（文案改了不该让回归红）。 */
    private static Map<String, Object> reactView(GameState g, GameEngine.Result res, long coinsBefore,
                                                 Map<String, Integer> bagBefore, List<String> discBefore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", res.kind);
        m.put("reactionId", res.reaction == null ? null : res.reaction.id());
        m.put("procId", res.proc == null ? null : res.proc.id());
        m.put("suggestId", res.suggest == null ? null : res.suggest.id());
        m.put("produced", new TreeMap<>(res.produced));
        m.put("exp", res.exp);
        m.put("ups", res.ups);
        m.put("eqBonus", res.eqBonus);
        m.put("yieldWarn", res.yieldWarn);
        m.put("lossVal", res.lossVal);
        m.put("coinsDelta", g.coins - coinsBefore);
        m.put("levelAfter", g.level);
        m.put("expAfter", g.exp);

        Map<String, Integer> delta = new TreeMap<>();
        Map<String, Integer> after = new TreeMap<>(g.bag);
        for (String k : after.keySet()) {
            int d = after.get(k) - bagBefore.getOrDefault(k, 0);
            if (d != 0) delta.put(k, d);
        }
        for (String k : bagBefore.keySet()) if (!after.containsKey(k)) delta.put(k, -bagBefore.get(k));
        m.put("bagDelta", delta);

        List<String> newly = new ArrayList<>(g.discovered.keySet());
        newly.removeAll(discBefore);
        Collections.sort(newly);
        m.put("discoveredNew", newly);
        return m;
    }

    private static Map<String, Object> repView(GameState g, ContentRegistry.Snapshot s, EconomyService eco) {
        EconomyService.Rep r = eco.repTier(g, s);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zh", r.zh); m.put("buyRate", r.buyRate); m.put("speed", r.speed);
        return m;
    }

    /* ================= 按向量摆存档 ================= */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o == null ? new LinkedHashMap<>() : (Map<String, Object>) o;
    }

    private static List<Object> list(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return new ArrayList<>(l);
        fail("期望数组，实际是 " + o.getClass().getSimpleName());
        return List.of();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static boolean bool(Object o) { return Boolean.TRUE.equals(o); }

    private static double num(Object o, double dft) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isEmpty()) { try { return Double.parseDouble(s); } catch (Exception ignored) { } }
        return dft;
    }

    private static GameState.Bench bench(Map<String, Object> bm) {
        GameState.Bench b = new GameState.Bench();
        b.vessel = str(orDefault(bm.get("vessel"), "beaker"));
        b.temp = str(orDefault(bm.get("temp"), "room"));
        b.electrolysis = bool(bm.get("electrolysis"));
        for (Map.Entry<String, Object> en : map(bm.get("placed")).entrySet()) {
            b.placed.put(en.getKey(), (int) num(en.getValue(), 0));
        }
        return b;
    }

    private static Object orDefault(Object o, Object d) { return o == null ? d : o; }

    private static GameState state(Map<String, Object> seed, Map<String, Object> bm) {
        GameState g = new GameState();
        g.coins = (long) num(seed.get("coins"), 5000);
        g.diamonds = (long) num(seed.get("diamonds"), 0);
        g.exp = (long) num(seed.get("exp"), 0);
        g.level = (int) num(seed.get("level"), 1);
        g.rep = (int) num(seed.get("rep"), 0);
        g.realMode = bool(seed.get("realMode"));
        g.packs.el = bool(seed.get("packsEl"));

        Map<String, Object> lab = map(seed.get("lab"));
        g.lab.storage = (int) num(lab.get("storage"), 0);
        g.lab.safety = (int) num(lab.get("safety"), 0);
        g.lab.bench = (int) num(lab.get("bench"), 0);

        for (Map.Entry<String, Object> en : map(seed.get("equipment")).entrySet()) {
            g.equipment.put(en.getKey(), bool(en.getValue()));
        }
        for (Map.Entry<String, Object> en : map(seed.get("vessels")).entrySet()) {
            Map<String, Object> vv = map(en.getValue());
            g.vessels.put(en.getKey(), new GameState.Vessel(bool(vv.get("owned")), (int) num(vv.get("tier"), 0)));
        }
        for (Map.Entry<String, Object> en : map(seed.get("bag")).entrySet()) {
            g.bag.put(en.getKey(), (int) num(en.getValue(), 0));
        }
        for (Map.Entry<String, Object> en : map(seed.get("discovered")).entrySet()) {
            Map<String, Object> dd = map(en.getValue());
            GameState.Discover d = new GameState.Discover();
            d.times = (int) num(dd.get("times"), 1);
            d.first = (long) num(dd.get("first"), 0);
            g.discovered.put(en.getKey(), d);
        }
        for (Map.Entry<String, Object> en : map(seed.get("reactionsKnown")).entrySet()) {
            g.reactionsKnown.put(en.getKey(), bool(en.getValue()));
        }
        for (Map.Entry<String, Object> en : map(seed.get("firstBonusTaken")).entrySet()) {
            g.firstBonusTaken.put(en.getKey(), bool(en.getValue()));
        }

        String room = str(orDefault(seed.get("room"), "inorganic"));
        g.rooms = new ArrayList<>(List.of(room));
        g.bi = 0;
        g.benchStates = new ArrayList<>(List.of(bench(bm)));

        // 行情必须原样接住：EconomyService.drift 只在 market.date 不是"今天"时重算，
        // 重算出来的价格是随日期变的数，钉不住任何期望值。
        g.market.date = str(orDefault(seed.get("marketDate"), GameState.dayKey(System.currentTimeMillis())));
        for (Map.Entry<String, Object> en : map(seed.get("drift")).entrySet()) {
            g.market.drift.put(en.getKey(), num(en.getValue(), 1));
        }
        return g;
    }

    /* ================= 比较：数字留浮点余量，其余按规范化字符串 ================= */
    private static void assertSame2(String what, Object want, Object got) {
        if (want == null || got == null) {
            assertEquals(want, got, what + "：一端为 null");
            return;
        }
        if (want instanceof Number && got instanceof Number) {
            double a = ((Number) want).doubleValue(), c = ((Number) got).doubleValue();
            assertTrue(Math.abs(a - c) < 1e-9, what + "：期望 " + a + "，服务端算出 " + c);
            return;
        }
        assertEquals(canon(want), canon(got), what);
    }

    /** prices / bonus 这种"探针 → 读数"的表：逐键比，报错时能直接看到是哪个物质的哪个价。 */
    private static void assertPrice(String what, int i, Map<String, Object> want, Map<String, Object> got) {
        assertEquals(want.get("id"), got.get("id"), what + "[" + i + "]：探针顺序与期望不符");
        for (String k : want.keySet()) {
            assertSame2(what + "[" + i + "]." + k, want.get(k), got.get(k));
        }
    }

    private static String canon(Object o) {
        if (o == null) return "null";
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)) return Long.toString((long) d);
            return Double.toString(d);
        }
        if (o instanceof Boolean || o instanceof Character) return o.toString();
        if (o instanceof Map<?, ?> m) {
            TreeMap<String, Object> t = new TreeMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) t.put(String.valueOf(en.getKey()), en.getValue());
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> en : t.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(en.getKey()).append("=").append(canon(en.getValue()));
            }
            return sb.append("}").toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (Object x : l) sb.append(canon(x)).append(",");
            return sb.append("]").toString();
        }
        return o.toString();
    }

    /* ================= dump 模式收尾 ================= */
    @AfterAll
    static void writeDump() throws Exception {
        if (System.getProperty("golden.dump") == null || DUMPED.isEmpty()) return;
        Path out = vectorsFile().getParent().resolve("java-dump.json");
        Files.write(out, OM.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new LinkedHashMap<>(DUMPED)).getBytes(StandardCharsets.UTF_8));
        System.out.println("[golden] 已写出服务端结算 " + out + "（" + DUMPED.size() + " 条）；"
                + "跑 node test/golden/run.js --merge 并进 vectors.json 后删掉这个文件。");
    }
}
