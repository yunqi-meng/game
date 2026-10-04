package com.chemera.server.game;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * 经济与子系统（state.js 经济部分 + panels.js 市场/订单/挂单/签到/答题/商店/升级/社交）的服务端权威移植。
 * 背包/哈希/经验/成就等基元复用 {@link GameEngine}，避免重复实现、保证与引擎一致。
 * 所有概率/随机取用注入的 rng（[0,1)），日期用调用方传入的 now（毫秒）。
 */
@Service
public class EconomyService {

    private final GameEngine engine;

    public EconomyService(GameEngine engine) { this.engine = engine; }

    static Map<String, Object> res(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static double round2(double x) { return Math.round(x * 100) / 100.0; }

    private static String dayKey(long now) { return GameState.dayKey(now); }

    /* ================= 行情 / 价格 ================= */
    double drift(GameState g, ContentRegistry.Snapshot s, String id, long now) {
        GameState.Market mk = g.market;
        String dk = dayKey(now);
        if (!dk.equals(mk.date)) {
            mk.date = dk;
            mk.drift = new LinkedHashMap<>();
            for (String k : s.substances().keySet()) {
                mk.drift.put(k, 1 + GameEngine.hash(k + dk) * 0.075);
            }
        }
        return mk.drift.getOrDefault(id, 1.0);
    }

    static final class Rep { final String zh; final double buyRate; final double speed;
        Rep(String z, double b, double s) { zh = z; buyRate = b; speed = s; } }

    Rep repTier(GameState g, ContentRegistry.Snapshot s) {
        int r = g.rep;
        if (r >= 100) return new Rep("荣誉会员", 1.08, 0.6);
        if (r >= 50) return new Rep("贵宾", 1.12, 0.75);
        if (r >= 20) return new Rep("常客", 1.16, 0.88);
        return new Rep("生面孔", s.config.buyRate(), 1);
    }

    boolean monthlyActive(GameState g, long now) { return now < g.monthly.until; }

    long buyPrice(GameState g, ContentRegistry.Snapshot s, String id, long now) {
        Content.Substance sub = s.substance(id);
        double price = sub == null ? 999999 : sub.price();
        return Math.max(1, Math.round(price * repTier(g, s).buyRate * drift(g, s, id, now)));
    }

    long sellPrice(GameState g, ContentRegistry.Snapshot s, String id, int q, long now) {
        Content.Substance sub = s.substance(id);
        if (sub == null) return 0;
        double p = sub.price() * s.config.sellRate() * s.config.qualityMult(q) * drift(g, s, id, now);
        if (!Boolean.TRUE.equals(g.firstBonusTaken.get(id))) p *= s.config.firstSellBonus();
        return Math.max(1, Math.round(p));
    }

    /* ================= 直售 / 购买 ================= */
    Map<String, Object> instantSell(GameState g, ContentRegistry.Snapshot s, String id, int q, int n, long now) {
        n = Math.min(n, engine.count(g, id, q));
        if (n <= 0) return res("ok", false, "msg", "没有可出售的数量");
        long price = sellPrice(g, s, id, q, now) * n;
        engine.takeItem(g, id, n, q);
        g.firstBonusTaken.put(id, true);
        g.coins += price;
        g.stats.sold += n; g.stats.trades++;
        engine.dailyEvent(g, "trade", 1); engine.checkAch(g, s);
        return res("ok", true, "price", price, "n", n);
    }

    Map<String, Object> buyMarket(GameState g, ContentRegistry.Snapshot s, long now, DoubleSupplier rng, String id, int amt) {
        Content.Substance sub = s.substance(id);
        if (sub == null || "SLAG".equals(id)) return res("ok", false, "msg", "商品不存在");
        boolean open = "element".equals(sub.kind()) ? engine.elementOpenByLevel(g, sub) : engine.compoundOpenByLevel(g, sub);
        if (!open) return res("ok", false, "msg", "该物质尚未到解锁等级");
        long cost = Math.round(buyPrice(g, s, id, now) * (long) amt);
        if (g.coins < cost) return res("ok", false, "msg", "金币不足");
        g.coins -= cost;
        engine.addItem(g, s, id, amt, 0, true);
        g.stats.trades++; engine.dailyEvent(g, "trade", 1);
        return res("ok", true, "cost", cost);
    }

    Map<String, Object> buyConsumable(GameState g, ContentRegistry.Snapshot s, String id, int n) {
        Content.Substance sub = s.substance(id);
        if (sub == null || !"consumable".equals(sub.kind())) return res("ok", false, "msg", "耗材不存在");
        long cost = (long) sub.price() * n;
        if (g.coins < cost) return res("ok", false, "msg", "金币不足");
        g.coins -= cost; engine.addItem(g, s, id, n, 0, true);
        return res("ok", true, "cost", cost);
    }

    Map<String, Object> buySpecial(GameState g, ContentRegistry.Snapshot s, long now, String id, boolean isSpecial) {
        List<Map<String, Object>> list = isSpecial ? g.market.specials : g.market.black;
        Map<String, Object> sp = list.stream().filter(x -> id.equals(x.get("id"))).findFirst().orElse(null);
        if (sp == null) return res("ok", false, "msg", "今日没有该特惠/黑市商品");
        int lim = ((Number) sp.getOrDefault("lim", 1)).intValue();
        int bought = ((Number) g.market.specialBuy.getOrDefault(id, 0)).intValue();
        if (bought >= lim) return res("ok", false, "msg", "今日限购已满");
        Content.Substance sub = s.substance(id);
        if (sub == null) return res("ok", false, "msg", "商品不存在");
        long cost;
        if (isSpecial) cost = buyPrice(g, s, id, now);
        else cost = Math.round(sub.price() * drift(g, s, id, now) * ((Number) sp.getOrDefault("prem", 1.6)).doubleValue());
        if (g.coins < cost) return res("ok", false, "msg", "金币不足");
        g.coins -= cost;
        g.market.specialBuy.put(id, bought + 1);
        engine.addItem(g, s, id, 1, 0, true);
        g.stats.trades++; engine.dailyEvent(g, "trade", 1);
        return res("ok", true, "cost", cost);
    }

    /* ================= 挂单 ================= */
    Map<String, Object> createListing(GameState g, ContentRegistry.Snapshot s, long now, DoubleSupplier rng,
                                      String id, int q, int n, double priceMult) {
        if (n <= 0 || engine.count(g, id, q) < n) return res("ok", false, "msg", "数量不足");
        int bottles = (int) Math.ceil(n / 10.0);
        if (engine.count(g, "reagentbottle", 0) < bottles)
            return res("ok", false, "msg", "需要试剂瓶 ×" + bottles + "（市场耗材区购买）");
        engine.takeItem(g, "reagentbottle", bottles, 0);
        engine.takeItem(g, id, n, q);
        double fee = monthlyActive(g, now) ? 0 : 0.10;
        long price = Math.round(sellPrice(g, s, id, q, now) * n * priceMult * (1 - fee));
        double dur = (90 + rng.getAsDouble() * 240) * repTier(g, s).speed * (n > 10 ? 1.3 : 1);
        GameState.Listing L = new GameState.Listing();
        L.id = id; L.q = q; L.n = n; L.price = price; L.mat = now + dur * 1000;
        g.listings.add(L);
        return res("ok", true, "msg", "已挂单：约 " + (int) Math.ceil(dur) + " 秒后商会结算", "secs", (int) Math.ceil(dur));
    }

    int processListings(GameState g, ContentRegistry.Snapshot s, long now, DoubleSupplier rng) {
        int settled = 0;
        List<GameState.Listing> keep = new ArrayList<>();
        for (GameState.Listing L : g.listings) {
            if (now < L.mat) { keep.add(L); continue; }
            settled++;
            Content.Substance sub = s.substance(L.id);
            double base = (sub == null ? 10 : sub.price()) * L.n * s.config.qualityMult(L.q);
            double ratio = L.price / Math.max(1, base);
            double prob = Math.max(0.1, Math.min(0.95, 1.35 - ratio));
            if (rng.getAsDouble() < prob) {
                g.coins += L.price;
            } else {
                engine.addItem(g, s, L.id, L.n, L.q, true);
            }
            g.stats.trades++;
        }
        g.listings = keep;
        return settled;
    }

    /* ================= 每日特惠 / 黑市 ================= */
    void rollMarketExtras(GameState g, ContentRegistry.Snapshot s, DoubleSupplier rng) {
        if (!g.market.specials.isEmpty()) return;
        List<String> pool = new ArrayList<>();
        for (Content.Substance sub : s.substances().values()) {
            if (!"SLAG".equals(sub.id()) && sub.level() >= 1 && sub.level() <= 2) pool.add(sub.id());
        }
        List<Map<String, Object>> sp = new ArrayList<>();
        for (int i = 0; i < 3 && !pool.isEmpty(); i++) {
            String id = pool.get((int) (rng.getAsDouble() * pool.size()));
            sp.add(res("id", id, "disc", round2(0.7 + rng.getAsDouble() * 0.2), "lim", 5 + (int) (rng.getAsDouble() * 16)));
        }
        g.market.specials = sp;
        List<String> rare = new ArrayList<>();
        for (Content.Substance sub : s.substances().values()) {
            if (!"SLAG".equals(sub.id()) && (sub.level() == 4 || ("element".equals(sub.kind()) && sub.price() >= 150)))
                rare.add(sub.id());
        }
        List<Map<String, Object>> blk = new ArrayList<>();
        int nBlk = g.level >= 11 ? 2 : 0;
        for (int j = 0; j < nBlk && !rare.isEmpty(); j++) {
            String rid = rare.get((int) (rng.getAsDouble() * rare.size()));
            blk.add(res("id", rid, "prem", round2(1.6 + rng.getAsDouble() * 0.6), "lim", 1 + (int) (rng.getAsDouble() * 3)));
        }
        g.market.black = blk;
        g.market.specialBuy = new LinkedHashMap<>();
    }

    /* ================= 商会订单 ================= */
    @SuppressWarnings("unchecked")
    void rollOrders(GameState g, ContentRegistry.Snapshot s, long now, boolean force, DoubleSupplier rng) {
        String dk = dayKey(now);
        if (!force && dk.equals(g.orders.date) && !g.orders.list.isEmpty()) return;
        g.orders.date = dk;
        List<Content.CompoundDef> pool = new ArrayList<>();
        List<Content.CompoundDef> high = new ArrayList<>();
        for (Content.CompoundDef c : s.compounds) {
            if ("SLAG".equals(c.id())) continue;
            if (c.level() <= 2) pool.add(c);
            else if (c.level() == 3) high.add(c);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < 5 && !pool.isEmpty(); i++) {
            Content.CompoundDef pick = (g.level >= 10 && i % 3 == 2 && !high.isEmpty())
                    ? high.get((int) (rng.getAsDouble() * high.size()))
                    : pool.get((int) (rng.getAsDouble() * pool.size()));
            String grade = i == 0 ? "normal" : (rng.getAsDouble() < 0.25 && pick.level() >= 2 ? "rare" : "normal");
            int need = 3 + (int) (rng.getAsDouble() * ("rare".equals(grade) ? 8 : 5));
            int q = "rare".equals(grade) ? 1 : 0;
            long pay = Math.round(pick.price() * need * ("rare".equals(grade) ? 1.5 : 1.25) * s.config.qualityMult(q));
            list.add(res("id", pick.id(), "zh", pick.zh(), "need", need, "q", q, "pay", pay, "grade", grade, "done", false));
        }
        g.orders.list = list;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> fulfillOrder(GameState g, ContentRegistry.Snapshot s, int index) {
        if (index < 0 || index >= g.orders.list.size()) return res("ok", false, "msg", "订单不存在");
        Map<String, Object> o = g.orders.list.get(index);
        if (Boolean.TRUE.equals(o.get("done"))) return res("ok", false, "msg", "订单已完成");
        String id = (String) o.get("id");
        int need = ((Number) o.get("need")).intValue();
        int q = ((Number) o.get("q")).intValue();
        int left = need;
        for (int k = 2; k >= q && left > 0; k--) left -= engine.takeItem(g, id, left, k);
        if (left > 0) return res("ok", false, "msg", "品质不足");
        o.put("done", true);
        g.rep += "rare".equals(o.get("grade")) ? 3 : 1;
        g.coins += ((Number) o.get("pay")).longValue();
        g.stats.trades++; g.stats.sold += need;
        engine.dailyEvent(g, "trade", 1); engine.checkAch(g, s);
        return res("ok", true, "pay", o.get("pay"), "rep", g.rep);
    }

    /* ================= 每日刷新 ================= */
    Map<String, Object> rollDaily(GameState g, ContentRegistry.Snapshot s, long now, DoubleSupplier rng) {
        Map<String, Object> evt = res();
        if (!dayKey(now).equals(g.daily.date)) {
            GameState.Daily d = new GameState.Daily(); d.date = dayKey(now);
            g.daily = d;
            g.market = new GameState.Market();
            rollOrders(g, s, now, true, rng);
            long stipend = 200 + (long) g.level * 20 + (long) g.rooms.size() * 400 + (monthlyActive(g, now) ? 800 : 0);
            g.coins += stipend;
            if (monthlyActive(g, now)) g.diamonds += 3;
            evt.put("stipend", stipend);
            evt.put("newDay", true);
        }
        if (g.orders.list.isEmpty() || !dayKey(now).equals(g.orders.date)) rollOrders(g, s, now, false, rng);
        rollMarketExtras(g, s, rng);
        int settled = processListings(g, s, now, rng);
        if (settled > 0) evt.put("listingsSettled", settled);
        return evt;
    }

    /* ================= 成就 / 任务 / 图鉴 领取 ================= */
    Map<String, Object> claimAch(GameState g, ContentRegistry.Snapshot s, String id, boolean dbl) {
        Content.AchievementDef a = s.achievement(id);
        if (a == null || Boolean.TRUE.equals(g.achClaimed.get(id)) || !engine.achDone(g, id))
            return res("ok", false, "msg", "尚不可领取");
        g.achClaimed.put(id, true);
        boolean used = consumeDblCoupon(g, dbl);
        long pay = a.rewardOr() * (used ? 2 : 1);
        g.coins += pay;
        return res("ok", true, "reward", pay, "dbl", used);
    }

    Map<String, Object> claimDaily(GameState g, ContentRegistry.Snapshot s, String id, boolean dbl) {
        Content.TaskDef t = s.tasks.stream().filter(x -> id.equals(x.id())).findFirst().orElse(null);
        if (t == null || Boolean.TRUE.equals(g.daily.claimed.get(id))
                || Math.min(t.goalOr(), g.daily.counters.getOrDefault(t.key(), 0)) < t.goalOr())
            return res("ok", false, "msg", "任务未完成");
        g.daily.claimed.put(id, true);
        boolean used = consumeDblCoupon(g, dbl);
        long pay = t.rewardOr() * (used ? 2 : 1);
        g.coins += pay;
        return res("ok", true, "reward", pay, "dbl", used);
    }

    /** 双倍倍率必须由当日广告券支付（看一次广告只翻倍一次），无券则按普通金额结算。 */
    private static boolean consumeDblCoupon(GameState g, boolean dbl) {
        if (!dbl || !Boolean.TRUE.equals(g.daily.claimed.get("__dblCoupon"))) return false;
        g.daily.claimed.put("__dblCoupon", false);
        return true;
    }

    Map<String, Object> claimMilestone(GameState g, ContentRegistry.Snapshot s, int i, DoubleSupplier rng) {
        List<Integer> ms = s.config.milestonesOr();
        if (i < 0 || i >= ms.size()) return res("ok", false, "msg", "无此节点");
        int need = ms.get(i);
        if (Boolean.TRUE.equals(g.milestones.get(String.valueOf(need))) || g.discovered.size() < need)
            return res("ok", false, "msg", "尚未达成");
        g.milestones.put(String.valueOf(need), true);
        g.coins += (long) need * 100;
        List<Content.CompoundDef> pool = new ArrayList<>();
        for (Content.CompoundDef c : s.compounds) if (c.level() == 2 && !"SLAG".equals(c.id())) pool.add(c);
        Map<String, Object> r = res("ok", true, "coins", (long) need * 100);
        if (!pool.isEmpty()) {
            Content.CompoundDef gift = pool.get((int) (rng.getAsDouble() * pool.size()));
            engine.addItem(g, s, gift.id(), 3, 0, true);
            r.put("gift", gift.zh());
        }
        return r;
    }

    /* ================= 提示道具 ================= */
    Content.Reaction hint(GameState g, ContentRegistry.Snapshot s, boolean spendCoin, DoubleSupplier rng) {
        if (g.hints > 0) g.hints--;
        else if (spendCoin && g.coins >= 300) g.coins -= 300;
        else return null;
        List<Content.Reaction> unknown = new ArrayList<>();
        for (Content.Reaction r : s.reactions) {
            if (!Boolean.TRUE.equals(g.reactionsKnown.get(r.id())) && r.lv() <= g.level + 3) unknown.add(r);
        }
        if (unknown.isEmpty()) { g.hints++; g.coins += 300; return null; }
        return unknown.get((int) (rng.getAsDouble() * unknown.size()));
    }

    /* ================= 签到 ================= */
    Map<String, Object> sign(GameState g, ContentRegistry.Snapshot s, long now, DoubleSupplier rng) {
        GameState.Sign sg = g.sign;
        String dk = dayKey(now);
        if (dk.equals(sg.last)) return res("ok", false, "msg", "今日已签到");
        String y = dayKey(now - 86400000L);
        sg.streak = (y.equals(sg.last) ? sg.streak : 0) + 1;
        sg.last = dk;
        int day = ((sg.streak - 1) % 7) + 1;
        List<String> pool = new ArrayList<>();
        for (String id : engine.marketPool(g, s)) {
            Content.Substance sub = s.substance(id);
            if (sub != null && "element".equals(sub.kind())) pool.add(id);
        }
        String gift = pool.isEmpty() ? null : pool.get((int) (rng.getAsDouble() * pool.size()));
        if (gift != null) engine.addItem(g, s, gift, 3, 0, false);
        int[] coinsArr = {100, 150, 200, 300, 400, 500, 800};
        int coins = coinsArr[day - 1];
        g.coins += coins;
        boolean jackpot = day == 7;
        if (jackpot) g.diamonds += 5;
        return res("ok", true, "day", day, "coins", coins, "gift", gift, "diamonds", jackpot ? 5 : 0);
    }

    /* ================= 社交 ================= */
    Map<String, Object> visitFriend(GameState g, ContentRegistry.Snapshot s, long now, String npcId) {
        Content.NpcDef npc = s.npc(npcId);
        if (npc == null) return res("ok", false, "msg", "好友不存在");
        GameState.Friend f = g.friends.computeIfAbsent(npcId, k -> new GameState.Friend());
        String dk = dayKey(now);
        if (dk.equals(f.lastVisit)) return res("ok", false, "again", true, "msg", "今日已拜访");
        f.lastVisit = dk;
        Map<String, Object> r = res("ok", true);
        if (npc.gift() != null) {
            int n = npc.gift().n() == null ? 1 : npc.gift().n();
            engine.addItem(g, s, npc.gift().id(), n, 0, false);
            r.put("gift", res("id", npc.gift().id(), "n", n));
        }
        g.stats.visits++;
        return r;
    }

    Map<String, Object> giftToFriend(GameState g, ContentRegistry.Snapshot s, String npcId, String id, int n, DoubleSupplier rng) {
        n = Math.min(n, engine.countAll(g, id));
        if (n <= 0) return res("ok", false, "msg", "没有可赠送的物质");
        for (int q = 2; q >= 0 && n > 0; q--) n -= engine.takeItem(g, id, n, q);
        g.rep += 1;
        Content.Substance sub = s.substance(id);
        int thanks = (int) Math.round((sub == null ? 20 : sub.price()) * 0.5 * (1 + rng.getAsDouble()));
        g.coins += thanks;
        GameState.Friend f = g.friends.computeIfAbsent(npcId, k -> new GameState.Friend());
        f.giftedTotal = (f.giftedTotal == null ? 0 : f.giftedTotal) + 1;
        engine.checkAch(g, s);
        return res("ok", true, "thanks", thanks, "rep", g.rep);
    }

    List<Map<String, Object>> leaderboard(GameState g, ContentRegistry.Snapshot s, long now) {
        String dk = dayKey(now);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Content.NpcDef n : s.npcs) {
            int wob = (int) Math.round(GameEngine.hash(n.id() + dk) * 6);
            rows.add(res("zh", n.zh(), "emoji", n.emoji(), "n", n.discoveredOr() + wob, "you", false));
        }
        rows.add(res("zh", "你", "emoji", "🧑‍🔬", "n", g.discovered.size(), "you", true));
        rows.sort((a, b) -> Integer.compare(((Number) b.get("n")).intValue(), ((Number) a.get("n")).intValue()));
        return rows;
    }

    /* ================= 挑战生成 ================= */
    Map<String, Object> startChallenge(GameState g, ContentRegistry.Snapshot s, DoubleSupplier rng) {
        List<Content.Reaction> cands = new ArrayList<>();
        for (Content.Reaction r : s.reactions) {
            if (Boolean.TRUE.equals(g.reactionsKnown.get(r.id()))) continue;
            if (r.lv() > g.level + 2) continue;
            boolean hasTarget = r.productsOr().keySet().stream()
                    .anyMatch(p -> { Content.Substance x = s.substance(p); return x != null && x.level() >= 1; });
            if (hasTarget) cands.add(r);
        }
        if (cands.isEmpty()) return null;
        Content.Reaction r = cands.get((int) (rng.getAsDouble() * cands.size()));
        String target = r.productsOr().keySet().stream()
                .filter(p -> { Content.Substance x = s.substance(p); return x != null && x.level() >= 1; })
                .findFirst().orElse(r.productsOr().keySet().iterator().next());
        GameState.Chal ch = new GameState.Chal();
        ch.reactId = r.id(); ch.target = target;
        for (Map.Entry<String, Integer> e : r.reactantsOr().entrySet())
            ch.given.put(e.getKey(), e.getValue() * (1 + (int) (rng.getAsDouble() * 2)));
        List<String> poolIds = new ArrayList<>();
        for (String id : s.substances().keySet()) if (!"SLAG".equals(id) && !ch.given.containsKey(id)) poolIds.add(id);
        for (int i = 0; i < 2 && !poolIds.isEmpty(); i++)
            ch.decoys.put(poolIds.get((int) (rng.getAsDouble() * poolIds.size())), 2);
        ch.steps = 0; ch.max = 4; ch.win = false; ch.reward = 800 + (long) g.level * 150;
        g.chal = ch;
        return res("reactId", ch.reactId, "target", ch.target, "reward", ch.reward);
    }

    /* ================= 升级 / 商店 ================= */
    Map<String, Object> buyVessel(GameState g, ContentRegistry.Snapshot s, String id) {
        Content.InstrumentDef i = s.instrument(id);
        if (i == null || !i.isVessel()) return res("ok", false, "msg", "仪器不存在");
        if (g.level < i.unlock()) return res("ok", false, "msg", "等级不足");
        if (engine.ownVessel(g, id)) return res("ok", false, "msg", "已拥有");
        if (g.coins < i.cost()) return res("ok", false, "msg", "金币不足");
        g.coins -= i.cost();
        g.vessels.put(id, new GameState.Vessel(true, 0));
        return res("ok", true, "cost", i.cost());
    }

    Map<String, Object> buyEquipment(GameState g, ContentRegistry.Snapshot s, String id) {
        Content.InstrumentDef i = s.instrument(id);
        if (i == null || i.isVessel()) return res("ok", false, "msg", "设备不存在");
        if (g.level < i.unlock()) return res("ok", false, "msg", "等级不足");
        if (Boolean.TRUE.equals(g.equipment.get(id))) return res("ok", false, "msg", "已装备");
        if (g.coins < i.cost()) return res("ok", false, "msg", "金币不足");
        g.coins -= i.cost();
        g.equipment.put(id, true);
        return res("ok", true, "cost", i.cost());
    }

    Map<String, Object> upgradeVessel(GameState g, ContentRegistry.Snapshot s, String id) {
        GameState.Vessel v = g.vessels.get(id);
        Content.InstrumentDef i = s.instrument(id);
        if (v == null || !v.owned || i == null) return res("ok", false, "msg", "未拥有该容器");
        if (v.tier >= 2) return res("ok", false, "msg", "已满级");
        long cost = (long) (i.cost() * s.config.tierUpCost(v.tier));
        if (g.coins < cost) return res("ok", false, "msg", "金币不足");
        g.coins -= cost; v.tier++;
        return res("ok", true, "tier", v.tier, "cost", cost);
    }

    Map<String, Object> upgradeLab(GameState g, ContentRegistry.Snapshot s, String key) {
        Content.LabUpgrade u = s.config.lab(key);
        if (u == null) return res("ok", false, "msg", "未知升级项");
        int lv = labLevel(g, key);
        if (lv >= u.max()) return res("ok", false, "msg", "已达上限");
        long cost = Math.round(u.baseCost() * Math.pow(u.growth(), lv));
        if (g.coins < cost) return res("ok", false, "msg", "金币不足");
        g.coins -= cost;
        setLabLevel(g, key, lv + 1);
        return res("ok", true, "lv", lv + 1, "cost", cost);
    }

    private int labLevel(GameState g, String key) {
        switch (key) { case "storage": return g.lab.storage; case "safety": return g.lab.safety; case "bench": return g.lab.bench; default: return 0; }
    }
    private void setLabLevel(GameState g, String key, int v) {
        switch (key) { case "storage": g.lab.storage = v; break; case "safety": g.lab.safety = v; break; case "bench": g.lab.bench = v; break; default: break; }
    }

    Map<String, Object> buyRoom(GameState g, ContentRegistry.Snapshot s, String id) {
        Content.RoomDef r = null;
        for (Content.RoomDef x : s.rooms) if (x.id().equals(id)) r = x;
        if (r == null) return res("ok", false, "msg", "房间不存在");
        if (g.rooms.contains(id)) return res("ok", false, "msg", "已拥有该房间");
        if (g.level < r.unlock()) return res("ok", false, "msg", "等级不足");
        if (g.coins < r.costOr()) return res("ok", false, "msg", "金币不足");
        g.coins -= r.costOr();
        g.rooms.add(id);
        return res("ok", true, "cost", r.costOr());
    }

    Map<String, Object> refine(GameState g, ContentRegistry.Snapshot s, int fromQ, int toQ, int need, long now) {
        if (!Boolean.TRUE.equals(g.equipment.get("spectrometer"))) return res("ok", false, "msg", "需要分光光度计");
        if (!monthlyActive(g, now)) { long wear = Math.min(g.coins, 200); g.coins -= wear; }
        String found = null;
        for (Map.Entry<String, Integer> e : g.bag.entrySet()) {
            String[] p = e.getKey().split("\\|");
            int q = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            if (q == fromQ && !"SLAG".equals(p[0]) && e.getValue() >= need) { found = e.getKey(); break; }
        }
        if (found == null) return res("ok", false, "msg", "没有持有量 ≥ " + need + " 的可提纯物质");
        String id = found.split("\\|")[0];
        engine.takeItem(g, id, need, fromQ);
        engine.addItem(g, s, id, 1, toQ, true);
        return res("ok", true, "id", id);
    }

    Map<String, Object> buyDiamondItem(GameState g, ContentRegistry.Snapshot s, String id, long now) {
        Content.ShopDef item = null;
        for (Content.ShopDef x : s.shop) if (x.id().equals(id)) item = x;
        if (item == null) return res("ok", false, "msg", "商品不存在");
        int cost = item.price();
        if (g.diamonds < cost) return res("ok", false, "msg", "钻石不足");
        if ("noad".equals(id) && g.noad) return res("ok", false, "msg", "已移除广告");
        if ("elpack".equals(id) && g.packs.el) return res("ok", false, "msg", "已拥有该礼包");
        g.diamonds -= cost;
        switch (id) {
            case "monthly": g.monthly.until = Math.max(now, g.monthly.until) + 30L * 86400000L; break;
            case "elpack": g.packs.el = true; break;
            case "noad": g.noad = true; break;
            case "hint5": g.hints += 5; break;
            default:
                if (id.startsWith("skin_")) {
                    String sk = id.substring(5);
                    if (!g.skins.owned.contains(sk)) g.skins.owned.add(sk);
                    g.skins.cur = sk;
                }
        }
        return res("ok", true, "diamonds", g.diamonds);
    }

    Map<String, Object> recharge(GameState g, ContentRegistry.Snapshot s, int tier) {
        List<Content.Recharge> rc = s.config.rechargeOr();
        if (tier < 0 || tier >= rc.size()) return res("ok", false, "msg", "无此充值档位");
        Content.Recharge r = rc.get(tier);
        g.diamonds += r.d();
        return res("ok", true, "gained", r.d(), "diamonds", g.diamonds);
    }

    /* ================= 答题 ================= */
    private static final Map<String, Double> GRADE_MULT =
            Map.of("小学", 1.0, "初中", 1.0, "高中", 1.2, "大学", 1.5);

    Content.QuizDef pickQuiz(GameState g, ContentRegistry.Snapshot s, String grade, DoubleSupplier rng) {
        List<Content.QuizDef> qs = new ArrayList<>();
        for (Content.QuizDef q : s.quizzes) {
            if (grade == null || "all".equals(grade) || grade.equals(q.grade() == null ? "初中" : q.grade())) qs.add(q);
        }
        if (qs.isEmpty()) qs = s.quizzes;
        if (qs.isEmpty()) return null;
        return qs.get((int) (rng.getAsDouble() * qs.size()));
    }

    Map<String, Object> answerQuiz(GameState g, ContentRegistry.Snapshot s, String quizId, int choice, DoubleSupplier rng) {
        Content.QuizDef q = s.quiz(quizId);
        if (q == null) return res("ok", false, "msg", "题目不存在");
        g.stats.quiz++;
        boolean correct = choice == q.answer();
        long reward = 0; int dropD = 0;
        if (correct) {
            String grade = q.grade() == null ? "初中" : q.grade();
            reward = Math.round(s.config.quizReward() * GRADE_MULT.getOrDefault(grade, 1.0));
            g.stats.quizOk++; engine.dailyEvent(g, "quiz", 1); g.coins += reward;
            double rate = "大学".equals(grade) ? 0.3 : "高中".equals(grade) ? 0.15 : 0.05;
            if (rng.getAsDouble() < rate) { dropD = 1; g.diamonds += 1; }
            engine.checkAch(g, s);
        }
        return res("ok", true, "correct", correct, "answer", q.answer(), "reward", reward, "dropD", dropD, "exp", q.exp());
    }
}
