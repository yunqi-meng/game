package com.chemera.server.game;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * 权威游戏服务：所有玩法意图的唯一入口。每次调用 = 载入存档 → 每日刷新(rollDaily) → 就地结算(引擎/经济) → 写回 → 返回 {state, revision, events}。
 * 客户端只发意图、渲染回执，不再本地计算——彻底“在线化”。
 * 反应现场的临时台（挑战/沙盒）用每 uid 的内存 {@link EngineCtx} 承载（tempBench/sandbox），并尽力回填进存档以便重启复原。
 */
@Service
public class GameService {

    private final Supplier<ContentRegistry.Snapshot> snap;
    private final GameEngine engine;
    private final EconomyService eco;
    private final GameStore store;
    private final Map<Long, EngineCtx> sessions = new ConcurrentHashMap<>();

    /** 随机源：生产用强随机；测试注入定值以获得确定性结算。 */
    DoubleSupplier rng = () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble();

    /** Spring 装配入口：另一个包私有的 Supplier 构造只给测试注入确定性快照，故须显式标注首选构造。 */
    @Autowired
    public GameService(ContentRegistry reg, GameEngine e, EconomyService eco, GameStore store) {
        this(reg::current, e, eco, store);
    }

    GameService(Supplier<ContentRegistry.Snapshot> snap, GameEngine e, EconomyService eco, GameStore store) {
        this.snap = snap; this.engine = e; this.eco = eco; this.store = store;
    }

    EngineCtx ctx(long uid) { return sessions.computeIfAbsent(uid, k -> new EngineCtx()); }

    /* ================= 读：整帧快照（含每日刷新落库） ================= */
    public Map<String, Object> state(long uid) { return act(uid, "state", Map.of()); }

    /* ================= 统一意图分发 ================= */
    public Map<String, Object> act(long uid, String intent, Map<String, Object> params) {
        ContentRegistry.Snapshot s = snap.get();
        long now = System.currentTimeMillis();
        GameState g = store.load(uid).orElseGet(() -> GameState.fresh(now));
        EngineCtx c = ctx(uid);
        engine.ensureBenches(g);               // 保证首帧即可渲染工作台（benchStates/bi 惰性建齐）
        reopenLiveScene(g, c);                 // 服务重启后从存档复原挑战现场（沙盒为纯瞬态）
        c.insured = g.insured;                 // 保险以存档为准，事故消耗后回写

        Map<String, Object> daily = eco.rollDaily(g, s, now, rng);   // 权威：进帧先跑每日刷新

        Object result = dispatch(g, s, c, now, intent, params == null ? Map.of() : params);

        syncLiveScene(g, c);                   // 挑战临时台回填进 g.chal.bench，供持久化/重启复原
        g.insured = c.insured;                 // 事故消耗保险 → 回写存档
        long rev = store.save(uid, g);

        List<Map<String, Object>> events = new ArrayList<>();
        if (!daily.isEmpty()) {
            Map<String, Object> de = new LinkedHashMap<>(daily);
            de.put("type", "daily");
            events.add(de);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", intent);
        if (result instanceof Map<?, ?> m) {
            m.forEach((k, v) -> ev.put(String.valueOf(k), v));
        } else if (result != null) {
            ev.put("value", result);
        }
        events.add(ev);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", g);
        out.put("revision", rev);
        out.put("result", result);
        out.put("events", events);
        if (c.tempBench != null) out.put("bench", c.tempBench);   // 挑战/沙盒临时现场不在存档里，单独回传给客户端渲染
        out.put("sandbox", c.sandboxActive);
        return out;
    }

    private Object dispatch(GameState g, ContentRegistry.Snapshot s, EngineCtx c, long now,
                            String intent, Map<String, Object> p) {
        switch (intent) {
            case "state":
                return Map.of("ok", true);

            /* ---------- 实验台 ---------- */
            case "bench.place": {
                GameEngine.PlaceResult r = engine.place(g, s, c, rng, str(p, "id"), integer(p, "n", 1));
                return r.ok ? Map.of("ok", true) : Map.of("ok", false, "msg", r.msg);
            }
            case "bench.takeBack": engine.takeBack(g, s, c, str(p, "id")); return Map.of("ok", true);
            case "bench.clear": engine.clear(g, s, c); return Map.of("ok", true);
            case "bench.temp": {
                String t = str(p, "temp");
                if (!engine.canTemp(g, t)) return Map.of("ok", false, "msg", "缺少对应加热设备");
                engine.setTemp(g, c, t);
                return Map.of("ok", true, "temp", t);
            }
            case "bench.electrolysis": {
                boolean on = bool(p, "on");
                if (on && !engine.canElectrolysis(g)) return Map.of("ok", false, "msg", "尚未购买电解装置");
                engine.setElectrolysis(g, c, on);
                return Map.of("ok", true);
            }
            case "bench.vessel":
                return engine.setVessel(g, s, c, str(p, "id")) ? Map.of("ok", true) : Map.of("ok", false, "msg", "尚未拥有该仪器");
            case "bench.switch":
                return engine.goBench(g, c, integer(p, "index", 0)) ? Map.of("ok", true) : Map.of("ok", false, "msg", "无效工作台");

            /* ---------- 反应执行（解析与结算全在服务端；客户端预览只是无状态展示辅助） ---------- */
            case "react": {
                c.multiplier = Math.max(1, integer(p, "multiplier", 1));
                if (p.containsKey("insured")) c.insured = bool(p, "insured");
                return reactResult(g, s, c, engine.react(g, s, c, rng));
            }

            /* ---------- 挑战 / 沙盒（临时台生命周期） ---------- */
            case "challenge.start": {
                Map<String, Object> r = eco.startChallenge(g, s, rng);
                if (r == null) return Map.of("ok", false, "msg", "暂无可用挑战");
                c.sandboxActive = false;
                c.tempBench = benchFrom(g.chal.bench);   // 新挑战：空现场台
                return withOk(r);
            }
            case "challenge.quit":
                engine.closeTempBench(c); g.chal = null; return Map.of("ok", true);
            case "challenge.revive": {                // 失败复活：+2 步并清失败标记
                if (g.chal == null || !g.chal.failed) return Map.of("ok", false, "msg", "当前无可复活挑战");
                g.chal.max += 2; g.chal.failed = false;
                return Map.of("ok", true, "max", g.chal.max);
            }
            case "sandbox.enter":
                c.sandboxActive = true; c.tempBench = new GameState.Bench(); return Map.of("ok", true);
            case "sandbox.exit":
                engine.closeTempBench(c); return Map.of("ok", true);

            /* ---------- 市场 / 背包 ---------- */
            case "market.buy": return eco.buyMarket(g, s, now, rng, str(p, "id"), integer(p, "amt", 1));
            case "market.consumable": return eco.buyConsumable(g, s, str(p, "id"), integer(p, "n", 1));
            case "market.special": return eco.buySpecial(g, s, now, str(p, "id"), true);
            case "market.black": return eco.buySpecial(g, s, now, str(p, "id"), false);
            case "market.sell": return eco.instantSell(g, s, str(p, "id"), integer(p, "q", 0), integer(p, "n", 1), now);

            /* ---------- 挂单 / 订单 ---------- */
            case "listing.create":
                return eco.createListing(g, s, now, rng, str(p, "id"), integer(p, "q", 0), integer(p, "n", 1), dbl(p, "mult", 1.0));
            case "order.fulfill": return eco.fulfillOrder(g, s, integer(p, "index", 0));

            /* ---------- 签到 / 答题 ---------- */
            case "sign": return eco.sign(g, s, now, rng);
            // 题目由服务端随机下发，且不带正确答案——答案只在 quiz.answer 的回执里揭晓（防改包透题）
            case "quiz.pickOne": {
                Content.QuizDef q = eco.pickQuiz(g, s, str(p, "grade"), rng);
                if (q == null) return Map.of("ok", false, "msg", "题库为空");
                c.quizId = q.id();
                return Map.of("ok", true, "id", q.id(), "grade", q.grade() == null ? "初中" : q.grade(),
                        "q", q.q(), "opts", q.optsOr(), "exp", q.exp() == null ? "" : q.exp());
            }
            case "quiz.answer": {
                String qid = str(p, "quizId");
                if (qid == null || !qid.equals(c.quizId)) return Map.of("ok", false, "msg", "请先点「再来一题」取题");
                c.quizId = null;
                return eco.answerQuiz(g, s, qid, integer(p, "choice", -1), rng);
            }

            /* ---------- 商店 / 充值 ---------- */
            case "shop.buy": return eco.buyDiamondItem(g, s, str(p, "id"), now);
            case "shop.recharge": return eco.recharge(g, s, integer(p, "tier", 0));

            /* ---------- 升级 / 提纯 ---------- */
            case "upgrade.vessel": return eco.buyVessel(g, s, str(p, "id"));
            case "upgrade.equipment": return eco.buyEquipment(g, s, str(p, "id"));
            case "upgrade.tier": return eco.upgradeVessel(g, s, str(p, "id"));
            case "upgrade.lab": return eco.upgradeLab(g, s, str(p, "key"));
            case "upgrade.room": return eco.buyRoom(g, s, str(p, "id"));
            case "refine": return eco.refine(g, s, integer(p, "fromQ", 0), integer(p, "toQ", 1), integer(p, "need", 5), now);

            /* ---------- 领取 ---------- */
            case "claim.ach": return eco.claimAch(g, s, str(p, "id"), bool(p, "dbl"));
            case "claim.daily": return eco.claimDaily(g, s, str(p, "id"), bool(p, "dbl"));
            case "claim.milestone": return eco.claimMilestone(g, s, integer(p, "index", 0), rng);

            /* ---------- 提示 / 社交 ---------- */
            case "hint": {
                Content.Reaction r = eco.hint(g, s, bool(p, "spendCoin"), rng);
                return r == null ? Map.of("ok", false, "msg", "暂无可提示的未知方程式")
                        : Map.of("ok", true, "eq", r.eq(), "rid", r.id());
            }
            case "friend.visit": return eco.visitFriend(g, s, now, str(p, "npcId"));
            case "friend.gift": return eco.giftToFriend(g, s, str(p, "npcId"), str(p, "id"), integer(p, "n", 1), rng);
            case "leaderboard": return eco.leaderboard(g, s, now);

            /* ---------- 模拟广告奖励（服务端记账，每日一次） ---------- */
            case "ad.bonus": {
                int idx = integer(p, "kind", 0);            // 0=事故慰问金 1=当日双倍领取券
                if (idx == 0) {
                    if (claimed(g, "__adBoom")) return Map.of("ok", false, "msg", "今日该奖励已领取");
                    g.daily.claimed.put("__adBoom", true);
                    g.coins += 500;
                    return Map.of("ok", true, "coins", 500);
                }
                if (idx == 1) {
                    if (claimed(g, "__adDaily") || claimed(g, "__dblCoupon"))
                        return Map.of("ok", false, "msg", "今日的双倍券已领过/已用掉");
                    g.daily.claimed.put("__adDaily", true);
                    g.daily.claimed.put("__dblCoupon", true);
                    return Map.of("ok", true, "coupon", true);
                }
                return Map.of("ok", false, "msg", "未知奖励类型");
            }

            /* ---------- 偏好设置（localStorage 已废弃，偏好也存服务端） ---------- */
            case "settings": return applySettings(g, c, p);
            case "reset": return resetSave(g, c, now);
            case "tutorial.step": {
                int to = integer(p, "to", g.tutorial + 1);
                long bonus = 0;
                if (to > g.tutorial) {
                    if (g.tutorial == 3) { bonus = s.config.tutorialCoins(); g.coins += bonus; }
                    g.tutorial = Math.min(5, to);
                }
                return Map.of("ok", true, "tutorial", g.tutorial, "bonus", bonus);
            }

            default:
                return Map.of("ok", false, "msg", "未知意图: " + intent);
        }
    }

    /** 重置存档：就地覆盖为新手档（revision 照常递增），并关闭挑战/沙盒现场。 */
    private Map<String, Object> resetSave(GameState g, EngineCtx c, long now) {
        org.springframework.beans.BeanUtils.copyProperties(GameState.fresh(now), g);
        engine.ensureBenches(g);
        c.tempBench = null;
        c.sandboxActive = false;
        return Map.of("ok", true);
    }

    /** 白名单偏好：只接受这几项，其余键忽略。 */
    private Map<String, Object> applySettings(GameState g, EngineCtx c, Map<String, Object> p) {
        if (p.containsKey("realMode")) g.realMode = bool(p, "realMode");
        if (p.containsKey("music")) g.music = bool(p, "music");
        if (p.containsKey("insured")) c.insured = g.insured = bool(p, "insured");
        if (p.containsKey("volSfx")) g.volSfx = clamp(integer(p, "volSfx", g.volSfx), 0, 100);
        if (p.containsKey("volMus")) g.volMus = clamp(integer(p, "volMus", g.volMus), 0, 100);
        if (p.containsKey("tutorial")) g.tutorial = clamp(integer(p, "tutorial", g.tutorial), 0, 5);
        String skin = str(p, "skin");
        if (skin != null && g.skins.owned.contains(skin)) g.skins.cur = skin;
        return Map.of("ok", true, "realMode", g.realMode, "tutorial", g.tutorial);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static boolean claimed(GameState g, String key) { return Boolean.TRUE.equals(g.daily.claimed.get(key)); }

    /* ================= 反应回执 ================= */
    private Map<String, Object> reactResult(GameState g, ContentRegistry.Snapshot s, EngineCtx c,
                                            GameEngine.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", "success".equals(r.kind) || "partial".equals(r.kind));
        m.put("kind", r.kind);
        Content.Reaction rx = r.reaction;
        if (rx != null) {
            m.put("rid", rx.id());
            m.put("eq", rx.eq());
            m.put("reagents", new ArrayList<>(rx.reactantsOr().keySet()));
            // 本次是否首次解锁该方程式（结算前 reactionsKnown 尚未写入时才有意义）
            m.put("discovered", r.eqBonus > 0);
        }
        if (r.proc != null) m.put("proc", r.proc.id());
        m.put("produced", r.produced);
        m.put("exp", r.exp);
        m.put("ups", r.ups);
        m.put("eqBonus", r.eqBonus);
        if (r.msg != null) m.put("msg", r.msg);
        m.put("yieldWarn", r.yieldWarn);
        m.put("lossVal", r.lossVal);
        if (r.suggest != null) m.put("suggest", r.suggest.id());
        if (r.chal != null) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("win", r.chal.win); ch.put("decoy", r.chal.decoy);
            ch.put("outOfSteps", r.chal.outOfSteps); ch.put("reward", r.chal.reward); ch.put("target", r.chal.target);
            m.put("chal", ch);
        }
        if (rx != null) m.put("worth", worth(s, r.produced));
        return m;
    }

    private static long worth(ContentRegistry.Snapshot s, Map<String, Integer> produced) {
        long v = 0;
        for (Map.Entry<String, Integer> e : produced.entrySet()) {
            Content.Substance sub = s.substance(e.getKey());
            if (sub != null) v += (long) sub.price() * e.getValue();
        }
        return v;
    }

    /* ================= 临时台 ↔ 存档 桥接 ================= */
    private void reopenLiveScene(GameState g, EngineCtx c) {
        if (c.tempBench != null) return;
        if (g.chal != null && !g.chal.win && !g.chal.failed) c.tempBench = benchFrom(g.chal.bench);
    }

    private void syncLiveScene(GameState g, EngineCtx c) {
        if (c.tempBench != null && g.chal != null && !g.chal.win) g.chal.bench = c.tempBench;
    }

    private static GameState.Bench benchFrom(GameState.Bench src) {
        GameState.Bench b = new GameState.Bench();
        if (src != null) {
            b.vessel = src.vessel; b.temp = src.temp; b.electrolysis = src.electrolysis;
            b.placed = new LinkedHashMap<>(src.placed);
        }
        return b;
    }

    /* ================= 参数小工具 ================= */
    private static Map<String, Object> withOk(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r); m.put("ok", true); return m;
    }
    private static String str(Map<String, Object> p, String k) { Object v = p.get(k); return v == null ? null : String.valueOf(v); }
    private static int integer(Map<String, Object> p, String k, int d) { Object v = p.get(k); return v instanceof Number n ? n.intValue() : d; }
    private static double dbl(Map<String, Object> p, String k, double d) { Object v = p.get(k); return v instanceof Number n ? n.doubleValue() : d; }
    private static boolean bool(Map<String, Object> p, String k) { return Boolean.TRUE.equals(p.get(k)); }
}
