package com.chemera.server.game;

import com.chemera.server.service.SaveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static com.chemera.server.game.EconomyService.res;

/**
 * 权威游戏服务：所有玩法意图的唯一入口。每次调用 = 载入存档 → 每日刷新(rollDaily) → 就地结算(引擎/经济) → 写回 → 返回 {state, revision, events}。
 * 客户端只发意图、渲染回执，不再本地计算——彻底“在线化”。
 * 反应现场的临时台（挑战/沙盒）用每 uid 的内存 {@link EngineCtx} 承载（tempBench/sandbox），并尽力回填进存档以便重启复原。
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /** 旧付费意图的统一回绝文案：给还在用旧包的玩家一条明路，而不是"未知意图"。 */
    static final String NO_PAYMENT_MSG = "本作已完全免费：原来需要充值/钻石购买的内容，改到【设置·广告】看激励视频获取";
    static final String AD_OLD_FLOW_MSG = "看广告已改为服务端回调确认：请在【设置·广告】里点广告位观看";

    private final Supplier<ContentRegistry.Snapshot> snap;
    private final GameEngine engine;
    private final EconomyService eco;
    private final GameStore store;
    private final AdService ads;
    /**
     * 幂等窗口（F2）：同一句意图被链路交付两次时，第二次退回第一次那一帧、不再结算一遍。
     * 只在 {@code POST /api/game/{intent}} 带 {@code seq} 时生效，见 {@link #act(long, long, String, Map)}。
     */
    private final IntentDedupe dedupe;
    /**
     * 玩法闸门：任何意图在结算前先过它（防沉迷时段 {@link CurfewGuard}）。
     * 做成函数接口而不是直接持有 guard，是因为闸门要能在单测里关掉——引擎结算测试关心的是玩法，
     * 时段判定的正确性由 {@code CurfewGuardTest} 自己钉住，两边都不必伪造整条链。
     */
    private final LongConsumer gate;
    private final Map<Long, EngineCtx> sessions = new ConcurrentHashMap<>();

    /** 随机源：生产用强随机；测试注入定值以获得确定性结算。 */
    DoubleSupplier rng = () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble();

    /** Spring 装配入口：另一个包私有的 Supplier 构造只给测试注入确定性快照，故须显式标注首选构造。 */
    @Autowired
    public GameService(ContentRegistry reg, GameEngine e, EconomyService eco, GameStore store,
                       AdService ads, CurfewGuard curfew, IntentDedupe dedupe) {
        this(reg::current, e, eco, store, ads, curfew::assertAllowed, dedupe);
    }

    GameService(Supplier<ContentRegistry.Snapshot> snap, GameEngine e, EconomyService eco,
                GameStore store, AdService ads) {
        this(snap, e, eco, store, ads, uid -> { });
    }

    GameService(Supplier<ContentRegistry.Snapshot> snap, GameEngine e, EconomyService eco,
                GameStore store, AdService ads, LongConsumer gate) {
        this(snap, e, eco, store, ads, gate, new IntentDedupe());
    }

    GameService(Supplier<ContentRegistry.Snapshot> snap, GameEngine e, EconomyService eco,
                GameStore store, AdService ads, LongConsumer gate, IntentDedupe dedupe) {
        this.snap = snap; this.engine = e; this.eco = eco; this.store = store; this.ads = ads;
        this.gate = gate; this.dedupe = dedupe;
    }

    EngineCtx ctx(long uid) { return sessions.computeIfAbsent(uid, k -> new EngineCtx()); }

    /* ================= 读：整帧快照（含每日刷新落库） ================= */
    public Map<String, Object> state(long uid) { return act(uid, "state", Map.of()); }

    /**
     * 不改玩法状态的意图：取帧、看广告中心、看排行榜、抽一题。
     *
     * <p>它们以前每次也要走一遍"整帧序列化 + UPDATE + 插一行全量历史"，客户端每 30 秒心跳式地拉一次
     * {@code state}，等于把同一份存档抄几百遍，还把 revision 白白推高。现在这类意图只在
     * "确实有东西要落"（当天首次刷新、有广告奖励到账、存档行还不存在）时才写盘。
     * 注意这是**服务端自己**判的只读白名单，跟客户端上送不送 revision 无关：意图链的真源一直是这里。
     */
    private static final Set<String> READ_ONLY = Set.of("state", "ad.status", "leaderboard", "quiz.pickOne");

    /** 撞号重放上限：真撞满三次，说明这个账号在几台设备上打得飞起，让玩家重试比无限自旋诚实。 */
    static final int CAS_RETRIES = 3;

    /** 一次意图结算的产物：要么是要回给客户端的整帧，要么是"撞号了，重读重放"。 */
    private static final class Outcome {
        Map<String, Object> out;         // null = 本轮写回被判冲突，什么都没落盘
        final List<Long> claimed = new ArrayList<>();   // 本轮抢到结算权的广告工单，写盘失败要退回去
    }

    /**
     * 统一意图分发：载入 → 每日刷新 → 结算 → <b>带号写回</b> → 返回 {state, revision, events}。
     *
     * <p>写回为什么要带号：这一段 load→mutate→save 中间没有事务也没有锁，同一账号两台设备
     * （手机 + 平板，或者卸载重装）各发各的意图时，后写的整帧会把先写的抹掉，玩家看到的是
     * "我练了一半的东西没了"。带 {@code revision} 的 UPDATE 让抢输的一方一行都写不进去，
     * 于是它只能重读最新帧、在自己的内存里重放同一个意图（{@link #actOnce}）再写。
     * 重放是安全的：意图是"做什么"，服务端每次都从最新状态重新结算一遍，不是叠加增量。
     *
     * <p>不带 {@code sid}/{@code seq} 的入口（内部 {@link #state(long)}、单测、后台直接调 API）
     * 等于"这一句只交付一次"，行为与幂等层落地前完全一致。
     */
    public Map<String, Object> act(long uid, String intent, Map<String, Object> params) {
        return act(uid, 0L, intent, params);
    }

    /**
     * HTTP 入口用的四参版本：多带 {@code sid}（登录态主键）与意图参数里的 {@code seq}（会话内单调序号）。
     *
     * <p>顺序是有意的：<b>幂等判断排在防沉迷闸门之前</b>。重复交付的那一次在服务端根本没执行，
     * 它要的只是"我上次那一句的结果"，此时拿"现在不在可玩时段"去挡，等于把已经算完的意图说成没算——
     * 玩家看到的会是"扣了钱没到货，还提示我不能玩"。真正决定玩法的是第一次交付，那一次照样过了闸门。
     *
     * <p>只读意图不进幂等窗口：{@code state} 这类心跳每 8～30 秒一次，缓存它们只会把写意图挤出窗口，
     * 而读本来就没有副作用可去重。
     */
    public Map<String, Object> act(long uid, long sid, String intent, Map<String, Object> raw) {
        long seq = IntentDedupe.seqOf(raw);
        Map<String, Object> p = IntentDedupe.withoutSeq(raw);
        if (seq <= 0 || sid <= 0 || READ_ONLY.contains(intent)) return run(uid, intent, p);
        return dedupe.execute(uid, sid, seq, () -> run(uid, intent, p));
    }

    /** 一句意图的完整生命周期：闸门 → 结算 → 撞号则重读重放。 */
    private Map<String, Object> run(long uid, String intent, Map<String, Object> p) {
        gate.accept(uid);                      // 青少年模式：时段外的意图一律回绝，含只读的 state 取帧
        p = p == null ? Map.of() : p;
        Outcome o = actOnce(uid, intent, p);
        for (int attempt = 1; o.out == null; attempt++) {
            requeue(o.claimed);                // 本轮到账的广告先退回 rewarded，重放时才能重新到账
            if (attempt > CAS_RETRIES) {
                log.warn("意图写回连续 {} 次撞号 uid={} intent={}", attempt - 1, uid, intent);
                return staleFrame(uid, intent);
            }
            o = actOnce(uid, intent, p);
        }
        return o.out;
    }

    /** 把本轮抢到的广告结算权退回待结算：写盘没成功，奖励就不该算发过。 */
    private void requeue(List<Long> claimed) {
        for (Long id : claimed) ads.requeue(id);
    }

    /** 撞号到上限时的回执：把库里<b>现在</b>真有的那一帧回给客户端，并明说这次意图没生效。 */
    private Map<String, Object> staleFrame(long uid, String intent) {
        GameStore.Frame f = store.loadFrame(uid).orElse(null);
        GameState g = f == null ? GameState.fresh(System.currentTimeMillis()) : f.state();
        engine.ensureBenches(g);
        EngineCtx c = ctx(uid);
        c.insured = g.insured;
        Map<String, Object> r = res("ok", false, "stale", true,
                "msg", "这份存档刚在别处被改动，这次操作没生效，请重试");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", g);
        out.put("revision", f == null ? 0 : f.revision());
        out.put("result", r);
        out.put("events", List.of(eventWith(intent, r)));
        out.put("sandbox", c.sandboxActive);
        out.put("stale", true);
        return out;
    }

    /** 单次"载入 → 结算 → 带号写回"。写回被判冲突时返回 {@code out == null} 的 Outcome。 */
    private Outcome actOnce(long uid, String intent, Map<String, Object> p) {
        Outcome o = new Outcome();
        ContentRegistry.Snapshot s = snap.get();
        long now = System.currentTimeMillis();
        // 号与帧出自同一次读：分两次读，中间别人的写会把号配到我们的旧帧上，CAS 就白做了
        GameStore.Frame frame = store.loadFrame(uid).orElse(null);
        GameState g = frame == null ? GameState.fresh(now) : frame.state();
        long base = frame == null ? 0 : frame.revision();
        EngineCtx c = ctx(uid);
        engine.ensureBenches(g);               // 保证首帧即可渲染工作台（benchStates/bi 惰性建齐）
        reopenLiveScene(g, c);                 // 服务重启后从存档复原挑战现场（沙盒为纯瞬态）
        c.insured = g.insured;                 // 保险以存档为准，事故消耗后回写

        // 广告奖励在服务端回调后才到账：进帧先 settle，运营改配置/回调晚到都不会丢奖励
        List<Map<String, Object>> adGrants = ads.settle(g, uid, now, o.claimed);
        Map<String, Object> daily = eco.rollDaily(g, s, now, rng);   // 权威：进帧先跑每日刷新

        Object result = dispatch(g, s, c, uid, now, intent, p, adGrants);

        syncLiveScene(g, c);                   // 挑战临时台回填进 g.chal.bench，供持久化/重启复原
        g.insured = c.insured;                 // 事故消耗保险 → 回写存档

        // 只读意图且这一帧没被刷新/结算改动过、存档行也已经存在 ⇒ 一个字都不必写
        boolean mustWrite = !READ_ONLY.contains(intent) || !daily.isEmpty() || !adGrants.isEmpty() || base == 0;
        long rev = base;
        if (mustWrite) {
            rev = store.saveCas(uid, g, base, "reset".equals(intent) ? "reset" : SaveService.SOURCE_INTENT);
            if (rev == GameStore.CONFLICT) {
                o.out = null;                  // 交回 act() 重读重放；claimed 由 act() 负责退回
                return o;
            }
        }

        List<Map<String, Object>> events = new ArrayList<>();
        if (!adGrants.isEmpty()) events.add(eventWith("ad", Map.of("granted", adGrants)));
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
        o.out = out;
        return o;
    }

    private Object dispatch(GameState g, ContentRegistry.Snapshot s, EngineCtx c, long uid, long now,
                            String intent, Map<String, Object> p, List<Map<String, Object>> adGrants) {
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
            case "challenge.revive": {                // 失败复活：消耗看激励视频攒下的次数，+2 步并清失败标记
                if (g.chal == null || !g.chal.failed) return Map.of("ok", false, "msg", "当前无可复活挑战");
                GameState.Ad ad = AdService.ensure(g);
                if (ad.revive <= 0)
                    return res("ok", false, "msg", "没有复活次数：到【设置·广告】看一段激励视频即可获得",
                            "needAd", true, "adKind", "revive");
                ad.revive -= 1;
                g.chal.max += 2; g.chal.failed = false;
                return Map.of("ok", true, "max", g.chal.max, "reviveLeft", ad.revive);
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

            /* ---------- 激励视频：本作唯一的"付费"替代，签发/回调/结算三段见 AdService ---------- */
            case "ad.status":
                return ads.view(g, s, uid, now, adGrants);
            case "ad.request":
                return ads.request(g, s, uid, str(p, "kind"), now);
            case "ad.devGrant":
                return ads.devGrant(g, uid, str(p, "ticket"));
            case "ad.exchange": {
                Map<String, Object> r = new LinkedHashMap<>(ads.exchange(g, s, str(p, "id"), now));
                r.put("view", ads.view(g, s, uid, now, adGrants));   // 兑换后当场回一份最新目录，省一次往返
                return r;
            }

            /* ---------- 商店 / 充值：已下线，旧客户端打过来只给指引 ---------- */
            case "shop.buy":
            case "shop.recharge":
                return res("ok", false, "msg", NO_PAYMENT_MSG, "adCenter", true);

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

            /* ---------- 旧「自证看广告」意图：已废除，只回指引（否则客户端说看过就发钱） ---------- */
            case "ad.bonus":
                return res("ok", false, "msg", AD_OLD_FLOW_MSG, "adCenter", true);

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

    /** 造一条带 type 的事件（type 放在最前，客户端按它分派）。 */
    private static Map<String, Object> eventWith(String type, Map<String, Object> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.putAll(fields);
        return m;
    }
    private static String str(Map<String, Object> p, String k) { Object v = p.get(k); return v == null ? null : String.valueOf(v); }
    private static int integer(Map<String, Object> p, String k, int d) { Object v = p.get(k); return v instanceof Number n ? n.intValue() : d; }
    private static double dbl(Map<String, Object> p, String k, double d) { Object v = p.get(k); return v instanceof Number n ? n.doubleValue() : d; }
    private static boolean bool(Map<String, Object> p, String k) { return Boolean.TRUE.equals(p.get(k)); }
}
