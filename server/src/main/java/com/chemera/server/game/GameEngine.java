package com.chemera.server.game;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * 反应引擎：engine.js 的服务端 Java 移植，全服务端权威。
 * 纯函数式设计：所有方法以 (GameState, ContentRegistry.Snapshot, EngineCtx, rng) 为输入，就地改存档并返回结果。
 * rng 为 [0,1) 随机源，测试注入定值以获得确定性结算。
 */
@Service
public class GameEngine {

    static final int MAX_LINES = 6;

    /* ================= 结果对象 ================= */
    public static final class Result {
        public String kind;                       // none|success|partial|boom|fail-cond|fail-chal
        public Content.Reaction reaction;
        public Content.ProcessDef proc;
        public Map<String, Integer> produced = new LinkedHashMap<>();
        public int exp;
        public int ups;
        public long eqBonus;
        public String msg;
        public boolean yieldWarn;
        public long lossVal;
        public Content.Reaction suggest;
        public ChalResult chal;
    }

    public static final class ChalResult {
        public boolean win, decoy, outOfSteps;
        public long reward;
        public String target;
        ChalResult(boolean win) { this.win = win; }
    }

    public static final class PlaceResult {
        public final boolean ok;
        public final String msg;
        PlaceResult(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
        static PlaceResult ok() { return new PlaceResult(true, null); }
        static PlaceResult fail(String m) { return new PlaceResult(false, m); }
    }

    /* ================= 候选（反应 或 工艺） ================= */
    static final class Candidate {
        final Content.Reaction r;
        final Content.ProcessDef p;
        Candidate(Content.Reaction r) { this.r = r; this.p = null; }
        Candidate(Content.ProcessDef p) { this.r = null; this.p = p; }
        boolean isProcess() { return p != null; }
        String id() { return r != null ? r.id() : p.id(); }
        Map<String, Integer> reactants() { return r != null ? r.reactantsOr() : p.reactantsOr(); }
        Map<String, Integer> products() { return r != null ? r.productsOr() : p.productsOr(); }
        String temp() { return r != null ? r.cond().tempOrRoom() : p.tempOrRoom(); }
        String catalyst() { return r != null ? r.cond().catalyst() : null; }
        boolean elec() { return r != null && r.cond().elec(); }
        List<String> instruments() { return r != null ? r.instrumentOr() : List.of(p.vessel()); }
        String type() { return r != null ? r.type() : p.type(); }
        List<String> fx() { return r != null ? r.fxOr() : p.fxOr(); }
        int lv() { return r != null ? r.lv() : 1; }   // 工艺视同 discoverLv=1
        int exp() { return r != null ? r.expOr() : p.expOr(); }
        boolean hazard() { return r != null && r.isHazard(); }
        boolean danger() { return r != null && r.isDanger(); }
        String dangerMsg() { return r != null ? r.dangerMsg() : null; }
    }

    /* ================= bench 访问 ================= */
    void ensureBenches(GameState g) {
        int n = Math.max(1, g.rooms.size());
        List<GameState.Bench> src = g.benchStates == null ? List.of() : g.benchStates;
        List<GameState.Bench> kept = new ArrayList<>();
        for (GameState.Bench b : src) if (b != null && b.vessel != null) kept.add(b);
        while (kept.size() < n) kept.add(new GameState.Bench());
        if (kept.size() > n) kept = new ArrayList<>(kept.subList(0, n));
        g.benchStates = kept;
        g.bi = Math.min(Math.max(0, g.bi), n - 1);
    }

    GameState.Bench cur(GameState g, EngineCtx ctx) {
        if (ctx.tempBench != null) return ctx.tempBench;
        ensureBenches(g);
        return g.benchStates.get(g.bi);
    }

    Content.RoomDef curRoom(GameState g, ContentRegistry.Snapshot s) {
        ensureBenches(g);
        int idx = Math.min(g.bi, g.rooms.size() - 1);
        String id = idx >= 0 && idx < g.rooms.size() ? g.rooms.get(idx) : "inorganic";
        Content.RoomDef r = s.room(id);
        return r != null ? r : (s.rooms.isEmpty() ? null : s.rooms.get(0));
    }

    boolean goBench(GameState g, EngineCtx ctx, int i) {
        if (ctx.tempBench != null) return false;
        ensureBenches(g);
        if (i < 0 || i >= g.benchStates.size()) return false;
        g.bi = i;
        return true;
    }

    void closeTempBench(EngineCtx ctx) { ctx.tempBench = null; ctx.sandboxActive = false; }

    /* ================= 匹配 ================= */
    static boolean subsetOrEqual(Map<String, Integer> need, Map<String, Integer> have) {
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /** 是否恰好按配比消耗完所有投放物（催化剂除外）。 */
    static boolean consumesExactly(Candidate x, Map<String, Integer> placed) {
        String cat = x.catalyst();
        Map<String, Integer> reactants = x.reactants();
        List<String> pk = new ArrayList<>();
        for (String k : placed.keySet()) if (!k.equals(cat)) pk.add(k);
        if (pk.size() != reactants.size()) return false;
        for (Map.Entry<String, Integer> e : reactants.entrySet()) {
            if (placed.getOrDefault(e.getKey(), 0) != e.getValue()) return false;
        }
        return true;
    }

    boolean extraPlacedAllowed(Candidate r, Map<String, Integer> placed) {
        String cat = r.catalyst();
        Map<String, Integer> reactants = r.reactants();
        for (String k : placed.keySet()) {
            if (reactants.containsKey(k) || k.equals(cat)) continue;
            return false;
        }
        return true;
    }

    boolean conditionOk(Candidate r, GameState.Bench b) {
        if (!r.temp().equals(b.temp)) return false;
        if (r.elec() != b.electrolysis) return false;
        String cat = r.catalyst();
        if (cat != null && b.placed.getOrDefault(cat, 0) <= 0) return false;
        if (!r.instruments().isEmpty() && !r.instruments().contains(b.vessel)) return false;
        return true;
    }

    List<Candidate> matches(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx) {
        GameState.Bench b = cur(g, ctx);
        boolean real = g.realMode;
        List<Candidate> out = new ArrayList<>();
        for (Content.Reaction r : s.reactions) {
            Candidate c = new Candidate(r);
            if (!subsetOrEqual(r.reactantsOr(), b.placed)) continue;
            if (!extraPlacedAllowed(c, b.placed)) continue;
            if (real && !conditionOk(c, b)) continue;
            out.add(c);
        }
        Content.ProcessDef p = processMatchRaw(g, s, ctx);
        if (p != null) out.add(0, new Candidate(p));
        return out;
    }

    /** processMatch 的原始 ProcessDef 版本，供 matches 前置插入使用。 */
    Content.ProcessDef processMatchRaw(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx) {
        GameState.Bench b = cur(g, ctx);
        if (ctx.tempBench != null && !ctx.sandboxActive) return null;
        for (Content.ProcessDef p : s.processes) {
            if (!p.vessel().equals(b.vessel)) continue;
            if (!subsetOrEqual(p.reactantsOr(), b.placed)) continue;
            if (!p.tempOrRoom().equals(b.temp)) continue;
            if (p.needEquip() != null && !Boolean.TRUE.equals(g.equipment.get(p.needEquip()))) continue;
            if (p.consume() != null && count(g, p.consume(), 0) <= 0 && !ctx.sandboxActive) continue;
            return p;
        }
        return null;
    }

    int maxMultiplier(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx, Candidate r) {
        GameState.Bench b = cur(g, ctx);
        int m = Integer.MAX_VALUE;
        int cap = batchCap(g, s, ctx);
        for (Map.Entry<String, Integer> e : r.reactants().entrySet()) {
            m = Math.min(m, b.placed.getOrDefault(e.getKey(), 0) / e.getValue());
        }
        return Math.max(1, Math.min(m == Integer.MAX_VALUE ? 1 : m, cap));
    }

    int batchCap(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx) {
        int cap = g.lab.bench >= 3 ? 20 : 10;
        Content.InstrumentDef v = s.instrument(cur(g, ctx).vessel);
        if (v != null) cap += v.batch();
        return cap;
    }

    /* ================= 危险 / 产率 ================= */
    Content.DangerDef rollDanger(ContentRegistry.Snapshot s, Map<String, Integer> placed) {
        for (Content.DangerDef d : s.dangers) {
            if (subsetOrEqual(d.reactantsOr(), placed)) return d;
        }
        return null;
    }

    boolean hasDanger(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx) {
        return rollDanger(s, cur(g, ctx).placed) != null;
    }

    static final class Env { double bonus; double accidentReduce; }

    Env yieldEnv(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx, Candidate r) {
        GameState.Bench b = cur(g, ctx);
        Env e = new Env();
        Content.InstrumentDef v = s.instrument(b.vessel);
        String rtype = r == null ? null : r.type();
        if (v != null) {
            e.bonus += v.yield();
            if ("spotplate".equals(v.id()) && "沉淀反应".equals(rtype)) e.bonus += 0.05;
            if ("sepfunnel".equals(v.id()) && "有机反应".equals(rtype)) e.bonus += 0.05;
        }
        Content.RoomDef room = curRoom(g, s);
        if (room != null && "hiprecision".equals(room.id())) e.bonus += 0.10;
        if (Boolean.TRUE.equals(g.equipment.get("spatula"))) e.bonus += 0.03;
        if (Boolean.TRUE.equals(g.equipment.get("thermometer"))) e.accidentReduce += 0.15;
        if (Boolean.TRUE.equals(g.equipment.get("stand"))) e.accidentReduce += 0.10;
        return e;
    }

    /* ================= 投放 / 取回 ================= */
    PlaceResult place(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx, DoubleSupplier rng, String id, int n) {
        GameState.Bench b = cur(g, ctx);
        if (n <= 0) n = 1;
        if (b.placed.size() >= MAX_LINES && !b.placed.containsKey(id)) {
            return PlaceResult.fail("容器最多容纳 " + MAX_LINES + " 种物质");
        }
        if (ctx.tempBench != null && g.chal != null && !g.chal.win) return placeChal(g, ctx, id, n);
        if (ctx.sandboxActive) { b.placed.merge(id, n, Integer::sum); return PlaceResult.ok(); }
        if (countAll(g, id) < n) return PlaceResult.fail("库存不足");
        int left = n;
        for (int q = 2; q >= 0 && left > 0; q--) left -= takeItem(g, id, left, q);
        if (left > 0) {
            if (n - left > 0) addItem(g, s, id, n - left, 0, false);
            return PlaceResult.fail("库存不足");
        }
        if (Boolean.TRUE.equals(g.equipment.get("dropper")) && rng.getAsDouble() < 0.15 && n == 1) {
            addItem(g, s, id, 1, 0, true);
        }
        b.placed.merge(id, n, Integer::sum);
        return PlaceResult.ok();
    }

    int givenLeft(GameState g, EngineCtx ctx, String id) {
        if (ctx.tempBench == null) return -1;
        GameState.Chal ch = g.chal;
        return (ch.given.getOrDefault(id, 0) + ch.decoys.getOrDefault(id, 0)) - ctx.tempBench.placed.getOrDefault(id, 0);
    }

    PlaceResult placeChal(GameState g, EngineCtx ctx, String id, int n) {
        GameState.Chal ch = g.chal;
        if (!ch.given.containsKey(id) && !ch.decoys.containsKey(id)) return PlaceResult.fail("挑战材料中没有该物质");
        if (givenLeft(g, ctx, id) < n) return PlaceResult.fail("挑战材料用完了");
        ctx.tempBench.placed.merge(id, n, Integer::sum);
        return PlaceResult.ok();
    }

    void takeBack(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx, String id) {
        GameState.Bench b = cur(g, ctx);
        Integer have = b.placed.get(id);
        if (have == null) return;
        if (!ctx.sandboxActive && ctx.tempBench == null) addItem(g, s, id, have, 0, false);
        b.placed.remove(id);
    }

    void clear(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx) {
        GameState.Bench b = cur(g, ctx);
        for (String id : new ArrayList<>(b.placed.keySet())) takeBack(g, s, ctx, id);
    }

    /** 温度档是否可用：客户端置灰只是预览，权威判定以此为准（防改包用高温）。 */
    boolean canTemp(GameState g, String t) {
        if ("room".equals(t)) return true;
        if ("heat".equals(t) || "ignite".equals(t)) return Boolean.TRUE.equals(g.equipment.get("lamp"));
        if ("highTemp".equals(t)) return Boolean.TRUE.equals(g.equipment.get("blowtorch"));
        return false;
    }

    boolean canElectrolysis(GameState g) { return Boolean.TRUE.equals(g.equipment.get("electrolyzer")); }

    void setTemp(GameState g, EngineCtx ctx, String t) { cur(g, ctx).temp = t; }
    void setElectrolysis(GameState g, EngineCtx ctx, boolean v) { cur(g, ctx).electrolysis = v; }
    boolean setVessel(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx, String id) {
        if (!ownVessel(g, id)) return false;
        cur(g, ctx).vessel = id;
        return true;
    }

    /* ================= 反应主流程 ================= */
    Result react(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx, DoubleSupplier rng) {
        Result res = new Result();
        GameState.Bench b = cur(g, ctx);
        if (b.placed.isEmpty()) { res.kind = "none"; res.msg = "容器中还没有物质"; return res; }

        // 挑战：混入干扰物质立即判负
        if (ctx.tempBench != null && g.chal != null && !ctx.sandboxActive) {
            GameState.Chal chd = g.chal;
            boolean decoy = chd.decoys.keySet().stream().anyMatch(id -> b.placed.getOrDefault(id, 0) > 0);
            if (decoy) {
                g.chal = null;
                closeTempBench(ctx);
                res.kind = "fail-chal";
                res.chal = new ChalResult(false); res.chal.decoy = true;
                return res;
            }
        }

        Content.InstrumentDef v = s.instrument(b.vessel);
        if (v != null && v.noHeatOr() && !"room".equals(b.temp)) {
            return boom(g, s, ctx, v.zh() + "不能加热！受热直接炸裂！", b);
        }

        List<Candidate> rs = matches(g, s, ctx);
        Content.DangerDef danger = rollDanger(s, b.placed);
        int safety = g.lab.safety;
        Env env = yieldEnv(g, s, ctx, rs.isEmpty() ? null : rs.get(0));

        if (danger != null && (rs.isEmpty() || rng.getAsDouble() < Math.max(0.05, 0.3 - safety * 0.04 - env.accidentReduce))) {
            String m = danger.msg() != null ? danger.msg() : "混合发生危险反应！";
            return boom(g, s, ctx, m, b);
        }
        if (rs.isEmpty()) {
            Content.Reaction near = null;
            for (Content.Reaction r : s.reactions) {
                Candidate c = new Candidate(r);
                if (subsetOrEqual(r.reactantsOr(), b.placed) && extraPlacedAllowed(c, b.placed)) { near = r; break; }
            }
            if (near != null) {
                res.kind = "fail-cond";
                res.msg = "物质组合正确，但反应条件不满足。检查温度 / 催化剂 / 电解 / 仪器。";
                res.suggest = near;
                return res;
            }
            return boom(g, s, ctx, "物质无法反应，生成一坨废渣……", b);
        }

        Candidate pick;
        boolean proc = rs.get(0).isProcess();
        if (proc) {
            pick = rs.get(0);
        } else {
            List<Candidate> exact = new ArrayList<>();
            for (Candidate x : rs) if (consumesExactly(x, b.placed)) exact.add(x);
            List<Candidate> cand = exact.isEmpty() ? new ArrayList<>(rs) : exact;
            if (!exact.isEmpty()) {
                cand.sort((a, c) -> {
                    int d = Integer.compare(c.reactants().size(), a.reactants().size());
                    return d != 0 ? d : Integer.compare(a.lv(), c.lv());
                });
            } else {
                cand.sort((a, c) -> Integer.compare(a.lv(), c.lv()));
            }
            pick = cand.get(0);
        }
        Content.ProcessDef pd = proc ? pick.p : null;
        res.reaction = proc ? null : pick.r;
        res.proc = pd;

        int mult = Math.max(1, Math.min(ctx.multiplier == 0 ? 1 : ctx.multiplier, maxMultiplier(g, s, ctx, pick)));

        if (!ctx.sandboxActive) {
            for (Map.Entry<String, Integer> e : pick.reactants().entrySet()) {
                int nv = b.placed.getOrDefault(e.getKey(), 0) - e.getValue() * mult;
                if (nv <= 0) b.placed.remove(e.getKey()); else b.placed.put(e.getKey(), nv);
            }
            if (pd != null && pd.consume() != null) takeItem(g, pd.consume(), 1, 0);
        } else {
            for (String k : new ArrayList<>(pick.reactants().keySet())) b.placed.remove(k);
        }

        int q = pd != null ? pd.quality() : Math.max(0, vesselTier(g, b.vessel));
        boolean yieldWarn = false;
        Map<String, Integer> produced = res.produced;
        boolean centrifuge = Boolean.TRUE.equals(g.equipment.get("centrifuge"));
        for (Map.Entry<String, Integer> e : pick.products().entrySet()) {
            String pid = e.getKey();
            int n = e.getValue() * mult;
            double failChance = Math.max(0, 0.2 - q * 0.08 - env.bonus - safety * 0.03);
            if (!ctx.sandboxActive && rng.getAsDouble() < failChance) {
                n = Math.max(1, (int) Math.floor(n * 0.5)); yieldWarn = true;
            }
            if (centrifuge && (pick.fx().contains("precip") || "沉淀反应".equals(pick.type()))) n += 1;
            if (!ctx.sandboxActive) addItem(g, s, pid, n, q, false);
            produced.merge(pid, n, Integer::sum);
        }
        res.yieldWarn = yieldWarn;

        boolean firstEq = !Boolean.TRUE.equals(g.reactionsKnown.get(pick.id())) && !proc;
        g.reactionsKnown.put(pick.id(), true);

        int expGain = pick.exp() * mult;
        Content.RoomDef room = curRoom(g, s);
        if (room != null && room.typesOr().contains(pick.type())) expGain = (int) Math.round(expGain * 1.3);
        if (Boolean.TRUE.equals(g.equipment.get("phmeter"))
                && ("中和反应".equals(pick.type()) || "复分解反应".equals(pick.type()))) {
            expGain = (int) Math.round(expGain * 1.2);
        }
        long eqBonus = 0;
        if (!ctx.sandboxActive && firstEq) {
            long sum = 0;
            for (String id2 : produced.keySet()) sum += discoverBonus(g, s, id2, true);
            eqBonus = Math.round(sum * 0.5);
            g.coins += eqBonus;
        }
        res.exp = expGain; res.eqBonus = eqBonus;
        int ups = ctx.sandboxActive ? 0 : addExp(g, expGain);
        res.ups = ups;
        if (ctx.sandboxActive) { g.stats.sandbox++; }
        else { g.stats.success++; dailyEvent(g, "success", 1); }
        checkAch(g, s);

        String partialMsg = null;
        if ((pick.hazard() || pick.danger()) && !ctx.sandboxActive
                && rng.getAsDouble() < Math.max(0.08, 0.5 - safety * 0.1 - env.accidentReduce)) {
            long fee = Math.min(g.coins, 100 + (long) (pick.exp() != 0 ? pick.exp() : 10) * 2);
            g.coins -= fee;
            partialMsg = "反应成功，但装置受到冲击！支付修复费 " + fee + " 金币";
        }

        // 挑战结算
        if (ctx.tempBench != null && g.chal != null && !ctx.sandboxActive) {
            GameState.Chal ch = g.chal;
            ch.steps++;
            ChalResult chalRes = null;
            if (produced.getOrDefault(ch.target, 0) > 0) {
                ch.win = true;
                g.coins += ch.reward; g.stats.challenges++; addExp(g, 60);
                chalRes = new ChalResult(true); chalRes.reward = ch.reward; chalRes.target = ch.target;
            } else if (ch.steps >= ch.max) {
                ch.failed = true;
                res.kind = "success";
                res.chal = new ChalResult(false); res.chal.outOfSteps = true;
                return res;
            }
            if (chalRes != null) {
                g.chal = null;
                closeTempBench(ctx);
                res.kind = "success"; res.chal = chalRes;
                return res;
            }
        }

        // 返还剩余（沙盒/临时台不返还）
        if (!ctx.sandboxActive && ctx.tempBench == null) {
            for (Map.Entry<String, Integer> e : new ArrayList<>(b.placed.entrySet())) {
                addItem(g, s, e.getKey(), e.getValue(), 0, false);
            }
        }
        b.placed.clear();

        res.kind = partialMsg != null ? "partial" : "success";
        res.msg = partialMsg;
        return res;
    }

    Result boom(GameState g, ContentRegistry.Snapshot s, EngineCtx ctx, String msg, GameState.Bench b) {
        Result res = new Result();
        res.kind = "boom";
        if (ctx.sandboxActive) {
            b.placed.clear();
            res.msg = "沙盒中该组合不反应（无任何损失）。";
            res.lossVal = 0;
            return res;
        }
        long loss = 0;
        for (Map.Entry<String, Integer> e : b.placed.entrySet()) {
            Content.Substance sub = s.substance(e.getKey());
            loss += (long) (sub != null ? sub.price() : 20) * e.getValue();
        }
        int safety = g.lab.safety;
        long dmg = Math.round(loss * (0.9 - safety * 0.12) * 0.5);
        String out = msg;
        if (count(g, "protectormask", 0) > 0 && dmg > 0) {
            takeItem(g, "protectormask", 1, 0);
            dmg = Math.round(dmg * 0.2);
            out += "（防护罩吸收了大部分冲击！）";
        }
        long refund = 0;
        if (ctx.insured) {
            refund = Math.round(loss * 0.5);
            ctx.insured = false;
            out += "（实验保险理赔 🪙" + refund + "）";
        }
        dmg = Math.min(g.coins, Math.max(0, dmg));
        g.coins = g.coins - dmg + refund;
        addItem(g, s, "SLAG", 1, 0, false);
        b.placed.clear();
        g.stats.boom++;
        if (ctx.tempBench != null && !ctx.sandboxActive && g.chal != null) {
            GameState.Chal ch = g.chal;
            ch.steps++;
            if (ch.steps >= ch.max) ch.failed = true;
        }
        checkAch(g, s);
        res.lossVal = loss;
        res.msg = out + (dmg > 0 ? " 损失原料并支付清理修复费 " + dmg + " 金币。" : " 幸好安全设施到位，损失不大。");
        return res;
    }

    /* ================= 解锁线 ================= */
    boolean elementOpenByLevel(GameState g, Content.Substance sub) {
        int lv = g.level;
        if (g.packs.el && ("lanthanide".equals(sub.cat()) || "actinide".equals(sub.cat()))) return true;
        if (lv >= 16) return true;
        if (lv >= 11) return sub.z() <= 56 || (sub.z() >= 72 && sub.z() <= 80);
        if (lv >= 6) return sub.z() <= 36;
        return sub.z() <= 20;
    }

    boolean compoundOpenByLevel(GameState g, Content.Substance sub) {
        int lv = g.level;
        int L = sub.level();
        return L <= 1 || (L == 2 && lv >= 6) || (L == 3 && lv >= 11) || (L == 4 && lv >= 16);
    }

    List<String> marketPool(GameState g, ContentRegistry.Snapshot s) {
        List<String> out = new ArrayList<>();
        for (Content.Substance sub : s.substances().values()) {
            if ("SLAG".equals(sub.id())) continue;
            boolean open = "element".equals(sub.kind()) ? elementOpenByLevel(g, sub) : compoundOpenByLevel(g, sub);
            if (open) out.add(sub.id());
        }
        return out;
    }

    /* ================= 存档基元（背包/等级/发现/日常/成就） ================= */
    static String itemKey(String id, int q) { return id + "|" + q; }

    int count(GameState g, String id, int q) { return g.bag.getOrDefault(itemKey(id, q), 0); }

    int countAll(GameState g, String id) {
        return count(g, id, 0) + count(g, id, 1) + count(g, id, 2);
    }

    int cap(GameState g, ContentRegistry.Snapshot s) {
        Content.LabUpgrade up = s.config.lab("storage");
        int step = up == null ? 50 : up.step();
        return 50 + g.lab.storage * step;
    }

    int addItem(GameState g, ContentRegistry.Snapshot s, String id, int n, int q, boolean silent) {
        String k = itemKey(id, q);
        int nv = g.bag.getOrDefault(k, 0) + n;
        int over = Math.max(0, nv - cap(g, s));
        if (over > 0) nv -= over;
        g.bag.put(k, nv);
        GameState.Discover d = g.discovered.get(id);
        if (d == null) {
            d = new GameState.Discover();
            d.first = System.currentTimeMillis();
            g.discovered.put(id, d);
            if (!silent) onDiscover(g, s, id);
        }
        d.times++;
        return over;
    }

    int takeItem(GameState g, String id, int n, int q) {
        String k = itemKey(id, q);
        int have = g.bag.getOrDefault(k, 0);
        n = Math.min(have, n);
        if (have - n <= 0) g.bag.remove(k); else g.bag.put(k, have - n);
        return n;
    }

    int vesselTier(GameState g, String id) {
        GameState.Vessel v = g.vessels.get(id);
        return v != null && v.owned ? v.tier : -1;
    }

    boolean ownVessel(GameState g, String id) {
        GameState.Vessel v = g.vessels.get(id);
        return v != null && v.owned;
    }

    int addExp(GameState g, int n) {
        g.exp += n;
        int ups = 0;
        while (g.exp >= Content.levelExp(g.level)) { g.exp -= Content.levelExp(g.level); g.level++; ups++; }
        return ups;
    }

    void dailyEvent(GameState g, String key, int n) {
        g.daily.counters.merge(key, n, Integer::sum);
    }

    void onDiscover(GameState g, ContentRegistry.Snapshot s, String id) {
        Content.Substance sub = s.substance(id);
        if (sub == null || "consumable".equals(sub.kind())) return;   // 耗材不计发现
        long bonus = sub.level() >= 1 ? discoverBonus(g, s, id, false) : 50;
        g.coins += bonus;
        dailyEvent(g, "discover", 1);
        checkAch(g, s);
    }

    long discoverBonus(GameState g, ContentRegistry.Snapshot s, String id, boolean forReaction) {
        Content.Substance sub = s.substance(id);
        if (sub == null) return 0;
        int lv = Math.max(1, sub.level());
        int[] r = s.config.discoverBonus(lv);
        double v = r[0] + (r[1] - r[0]) * Math.abs(hash(id));
        v = Math.round(v / 10.0) * 10;
        return forReaction ? Math.round(v * 0.5) : (long) v;
    }

    /** FNV-1a 风格哈希，映射到 [-1,1)，与 state.js hash() 完全一致。 */
    static double hash(String str) {
        int h = 0x811C9DC5;   // 2166136261 的 32 位补码形式（FNV offset basis）
        for (int i = 0; i < str.length(); i++) {
            h ^= str.charAt(i);
            h = h * 16777619;   // 32 位溢出等价于 Math.imul
        }
        // 注意：JS 的 `h >>> 0` 得到无符号 32 位数值；Java 中 `h >>> 0` 仍是 int（高位置 1 时为负），
        // 若直接 `% 2000` 会得到负数，破坏 [-1,1) 约定。用 toUnsignedLong 复刻 JS 无符号语义。
        return (Integer.toUnsignedLong(h) % 2000) / 1000.0 - 1;
    }

    /** 达成检测：返回本次新达成、尚未领取的成就 id（不改变状态，供上层生成事件）。 */
    List<String> checkAch(GameState g, ContentRegistry.Snapshot s) {
        List<String> done = new ArrayList<>();
        for (Content.AchievementDef a : s.achievements) {
            if (!Boolean.TRUE.equals(g.achClaimed.get(a.id())) && achDone(g, a.id())) done.add(a.id());
        }
        return done;
    }

    boolean achDone(GameState g, String id) {
        int disc = g.discovered.size();
        switch (id) {
            case "aFirst": return g.stats.success >= 1;
            case "aWater": return g.discovered.containsKey("H2O");
            case "aGold": return g.discovered.containsKey("Au");
            case "aBoom": return g.stats.boom >= 1;
            case "aS100": return g.stats.success >= 100;
            case "aD20": return disc >= 20;
            case "aD80": return disc >= 80;
            case "aD200": return disc >= 200;
            case "aEq30": return g.reactionsKnown.size() >= 30;
            case "aLv10": return g.level >= 10;
            case "aLv20": return g.level >= 20;
            case "aRich": return g.coins >= 50000;
            case "aOrganic": return g.discovered.containsKey("CH3COOC2H5");
            case "aAqua": return g.discovered.containsKey("aqua_regia");
            case "aQuiz50": return g.stats.quizOk >= 50;
            case "aSnake": return Boolean.TRUE.equals(g.reactionsKnown.get("R141"));
            case "aRep": return g.rep >= 50;
            case "aChallenge": return g.stats.challenges >= 1;
            case "aSandbox": return g.stats.sandbox >= 5;
            default: return false;
        }
    }
}
