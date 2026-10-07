package com.chemera.server.game;

import com.chemera.server.entity.AdTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

import static com.chemera.server.game.EconomyService.res;

/**
 * 激励视频经济：本作唯一的获取途径，取代原来的充值/钻石商店。
 *
 * <p>三段式，玩家端一句话都说了不算：
 * <ol>
 *   <li>{@link #request} 签发工单（随机 ticket 当作广告 SDK 的 extra），闸门全在这里：开关、等级、
 *       单位每日次数、单位冷却、每日总数、同位一次只挂一张；</li>
 *   <li>{@link #callback} 由广告网络（TapADN / Dirichlet SSP）在服务器侧回调，验签通过才置 rewarded，
 *       trans_id 唯一 + 条件 UPDATE 让重复回调与并发只算一次；</li>
 *   <li>{@link #settle} 在下一次取帧时把 rewarded 的行结算进存档，置 settled。</li>
 * </ol>
 *
 * <p>奖励在签发时就定格写进工单（reward/amount/points），所以运营中途改配置不会追溯改变已承诺的发放，
 * 也不会让"看了一半广告"的玩家拿到另一个数。
 *
 * <p>dev-mode（{@code chemera.ad.dev-mode=true}，需要显式挂上 {@code dev} profile）是给本机回归和浏览器演示用的自证通道：
 * 客户端拿着 ticket 直接调用 {@link #devGrant} 即可完成 rewarded。它让"任何人白拿奖励"成为可能，
 * 因此 {@link com.chemera.server.config.ProdHardening} 在 prod profile 下检测到它就拒绝启动，
 * 非 prod 又没挂 dev profile 时同样拒绝启动（漏一个 profile 就等于公网开着提款机）。
 * 注意它<b>不放开 {@link #callback}</b>：那条路径没有玩家身份，口令缺失时一律拒。
 */
@Service
public class AdService {

    private static final Logger log = LoggerFactory.getLogger(AdService.class);

    public static final String ISSUED = "issued";
    public static final String REWARDED = "rewarded";
    public static final String SETTLED = "settled";
    public static final String EXPIRED = "expired";

    /** 与 {@code PlayerAssetService} 的封顶同量级：广告发放也不该越过这两条线。 */
    static final long COIN_CAP = 1_000_000_000L;
    static final long DIAMOND_CAP = 1_000_000L;
    /** 一次最多结算这么多张，防止极端堆积时一个请求写脏整帧。 */
    static final int SETTLE_BATCH = 20;

    private static final Map<String, String> SKIN_ZH = Map.of(
            "default", "默认·清水蓝", "cyber", "赛博纪元", "retro", "复古炼金");

    private final AdTicketStore tickets;
    private final String securityKey;
    private final String signTemplate;
    private final boolean upperHex;
    private final boolean devMode;
    /** TapADN 推广位：激励视频一般全站共用一个 spaceId，运营要分位再按 kind 覆盖。 */
    private final String spaceId;
    private final SecureRandom rng = new SecureRandom();

    /**
     * 时间源：生产用系统时钟，回归注入假时钟才能演"过期/跨日/冷却"这些和墙钟绑死的分支
     * （回调判定过期用的是墙钟，测试里签发的工单却是测试时刻，不注入就只能永远判成已过期）。
     * 与 {@code GameService.rng} 同一套做法：包级可见，只在测试里改。
     */
    java.util.function.LongSupplier nowClock = System::currentTimeMillis;

    public AdService(AdTicketStore tickets,
                     @Value("${chemera.ad.security-key:}") String securityKey,
                     @Value("${chemera.ad.sign-template:{transId}{key}}") String signTemplate,
                     @Value("${chemera.ad.sign-hex:lower}") String signHex,
                     @Value("${chemera.ad.dev-mode:false}") boolean devMode,
                     @Value("${chemera.ad.space-id:}") String spaceId) {
        this.tickets = tickets;
        this.securityKey = securityKey == null ? "" : securityKey.trim();
        this.signTemplate = signTemplate == null || signTemplate.isBlank() ? "{transId}{key}" : signTemplate;
        this.upperHex = "upper".equalsIgnoreCase(signHex == null ? "" : signHex.trim());
        this.devMode = devMode;
        this.spaceId = spaceId == null ? "" : spaceId.trim();
    }

    public boolean devMode() { return devMode; }

    /**
     * 能不能真的发奖励：验签口令到位，或者明确在演示模式下。
     * 口令没配时不签发工单——玩家的"看广告"按钮不该点了没下文。
     */
    public boolean grantReady() { return devMode || !securityKey.isBlank(); }

    /* ================= 1. 签发 ================= */

    Map<String, Object> request(GameState g, ContentRegistry.Snapshot s, long uid, String kind, long now) {
        Content.AdConfig cfg = s.config.adOr();
        if (!cfg.on()) return res("ok", false, "msg", "广告中心暂未开放");
        if (!grantReady()) {
            log.warn("激励视频未配置验签口令（CHEMERA_AD_SECURITY_KEY），本次签发已拒绝 uid={} kind={}", uid, kind);
            return res("ok", false, "msg", "广告中心暂未开放");
        }
        Content.AdSlot slot = cfg.slot(kind);
        if (slot == null) return res("ok", false, "msg", "没有这个广告位");
        if (slot.dailyOr() <= 0) return res("ok", false, "msg", "该广告位已下线");
        if (g.level < cfg.minLevelOr()) return res("ok", false, "msg", "Lv." + cfg.minLevelOr() + " 才开放广告奖励");

        GameState.Ad ad = ensure(g);
        rollAdDay(ad, now);
        if (ad.dayCount(slot.kind()) >= slot.dailyOr())
            return res("ok", false, "msg", "今日该奖励已领完，明天再来");
        if (ad.todayTotal() >= cfg.dailyTotalOr())
            return res("ok", false, "msg", "今日观看次数已用完（上限 " + cfg.dailyTotalOr() + " 次）");
        long cooldownMs = slot.cooldownOr() * 1000L;
        long last = ad.lastAt.getOrDefault(slot.kind(), 0L);
        if (cooldownMs > 0 && now - last < cooldownMs)
            return res("ok", false, "msg", "冷却中，请 " + ((cooldownMs - (now - last) + 999) / 1000) + " 秒后再看",
                    "cooldownMs", cooldownMs - (now - last));

        // 同一位只允许挂一张未完成工单：否则"囤券→一次性刷完回调"会越过每日上限。
        // 到不了 rewarded（取帧开头就 settle 了），所以这里能查到的一定是 issued。
        AdTicket live = tickets.live(uid, slot.kind());
        if (live != null)
            return res("ok", false, "msg", "上一次观看还没结束，先看完或等它过期", "ticket", live.getTicket());

        tickets.expireStale(ldt(now));
        AdTicket t = new AdTicket();
        t.setTicket(randomToken());
        t.setUserId(uid);
        t.setKind(slot.kind());
        t.setReward(slot.reward());
        t.setAmount(slot.amountOr());
        t.setPoints(cfg.viewPointsOr());
        t.setStatus(ISSUED);
        t.setSpaceId(spaceId);
        // 与 expires_at 同一支钟：这一行的"今天"由意图时刻说了算，不由库的时区说了算
        t.setIssuedAt(ldt(now));
        t.setExpiresAt(ldt(now + cfg.ttlSecOr() * 1000L));
        tickets.issue(t);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ticket", t.getTicket());
        out.put("kind", slot.kind());
        out.put("zh", slot.zh());
        out.put("reward", slot.reward());
        out.put("amount", slot.amountOr());
        out.put("points", cfg.viewPointsOr());
        out.put("rewardText", rewardText(slot.reward(), slot.amountOr(), null));
        out.put("spaceId", spaceId);
        out.put("devMode", devMode);
        out.put("ttlSec", cfg.ttlSecOr());
        return out;
    }

    /* ================= 2. 广告网络回调 ================= */

    /**
     * 处理服务器回调。入参来自广告网络（TapADN 文档给出 pid / user_id / trans_id / extra / sign）。
     * 返回 {ok, code, msg}：ok=true 表示"这个交易号我已经认了"，重复回调也必须回 ok，否则平台会一直重试。
     */
    public Map<String, Object> callback(Map<String, String> p) {
        String transId = trim(p.get("trans_id"));
        String extra = trim(p.get("extra"));
        String userId = trim(p.get("user_id"));
        String pid = trim(p.get("pid"));
        String sign = trim(p.get("sign"));
        if (transId.isEmpty() || extra.isEmpty()) return res("ok", false, "code", "param", "msg", "参数不完整");
        // 口令缺失时这条路径一律拒，连演示模式也不例外：回调不带玩家身份，签名就是它唯一的凭证，
        // "没口令就照单全收"等于把发钱接口开放给任何会 POST 的人。
        // 本机演示要靠自证通道的话走 devGrant——那条带玩家 JWT，工单归属查得出来。
        if (securityKey.isBlank()) {
            log.warn("激励视频回调被拒：未配置验签口令（trans={} ip 见访问日志）", transId);
            return res("ok", false, "code", "key", "msg", "未配置验签口令");
        }
        if (!verifySign(sign, transId, userId, pid, extra)) {
            log.warn("激励视频回调验签失败 trans={} pid={} uid={}", transId, pid, userId);
            return res("ok", false, "code", "sign", "msg", "签名不匹配");
        }
        AdTicket t = tickets.byTicket(extra);
        if (t == null) return res("ok", false, "code", "ticket", "msg", "工单不存在");
        if (!userId.isEmpty() && !userId.equals(String.valueOf(t.getUserId())))
            return res("ok", false, "code", "user", "msg", "工单归属不符");
        if (REWARDED.equals(t.getStatus()) || SETTLED.equals(t.getStatus()))
            return res("ok", true, "code", "dup", "msg", "已处理过");       // 幂等：平台重试不该再发一遍
        if (!ISSUED.equals(t.getStatus())) return res("ok", false, "code", t.getStatus(), "msg", "工单已失效");
        if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(ldt(nowClock.getAsLong()))) {
            tickets.expireStale(ldt(nowClock.getAsLong()));
            return res("ok", false, "code", "expired", "msg", "工单已过期");
        }
        if (!tickets.markRewarded(t.getId(), transId)) return res("ok", true, "code", "dup", "msg", "已处理过");
        log.info("激励视频完成 uid={} kind={} trans={} reward={} amount={}",
                t.getUserId(), t.getKind(), transId, t.getReward(), t.getAmount());
        return res("ok", true, "code", "100000", "msg", "success");
    }

    /** 演示通道：本机回归/浏览器预览用，prod 起不来（见 ProdHardening）。 */
    Map<String, Object> devGrant(GameState g, long uid, String ticket) {
        if (!devMode) return res("ok", false, "msg", "当前环境不支持演示发放");
        String t = trim(ticket);
        if (t.isEmpty()) return res("ok", false, "msg", "缺少工单号");
        AdTicket tk = tickets.byTicket(t);
        if (tk == null || tk.getUserId() == null || tk.getUserId() != uid)
            return res("ok", false, "msg", "工单不存在或不属于你");
        if (ISSUED.equals(tk.getStatus())) tickets.markRewarded(tk.getId(), "dev-" + t.substring(0, Math.min(8, t.length())));
        return res("ok", true);
    }

    /**
     * 验签：把 trans_id 与口令按模板拼起来做 SHA-256。
     * 模板可配是因为 Dirichlet 文档只写了"trans_id 结合安全密钥做 SHA256"，没写死拼接顺序——
     * 对接时改 {@code chemera.ad.sign-template} 即可，不必改代码（占位符 {transId} {userId} {pid} {extra} {key}）。
     *
     * <p>{@code chemera.ad.sign-hex} 描述的是<b>平台回传的十六进制大小写</b>，所以比对时两边都要按它归一：
     * 先前写成"我方算大写、平台串转小写"，一旦运营真切成 upper 就永远验不过（广告播完却不发奖）。
     */
    boolean verifySign(String sign, String transId, String userId, String pid, String extra) {
        if (sign == null || sign.isBlank()) return false;
        String want = sha256(signTemplate
                .replace("{transId}", transId)
                .replace("{userId}", userId)
                .replace("{pid}", pid)
                .replace("{extra}", extra)
                .replace("{key}", securityKey));
        String wantHex = upperHex ? want.toUpperCase(java.util.Locale.ROOT) : want;
        String got = upperHex ? sign.trim().toUpperCase(java.util.Locale.ROOT) : sign.trim().toLowerCase(java.util.Locale.ROOT);
        return MessageDigest.isEqual(wantHex.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String randomToken() {
        byte[] b = new byte[16];
        rng.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /* ================= 3. 结算进存档 ================= */

    /**
     * 把已回调但未结算的工单兑现。返回这次到账的明细，客户端拿来弹"奖励已到账"。
     * 放在每次取帧最前面调用，所以回调晚于请求到达也不会丢奖励。
     */
    List<Map<String, Object>> settle(GameState g, long uid, long now) {
        return settle(g, uid, now, null);
    }

    /**
     * 同上，另外把"本轮真的抢到结算权"的工单 id 回填进 {@code claimedOut}。
     *
     * <p>调用方（GameService）拿它做一件很具体的事：这一批奖励只是改在内存里的 GameState 上，
     * 存档那一帧还没落盘。落盘失败或被 CAS 判冲突时，必须把这些工单退回 {@code rewarded}
     * （{@link AdTicketStore#requeueSettled}），下一帧才会重新到账——否则"广告看完了、奖励记在一份
     * 作废的帧里"，玩家的东西永久丢，而且日志里干干净净看不出来。
     * 传 null 表示调用方不关心（老的单测路径就是这个语义）。
     */
    List<Map<String, Object>> settle(GameState g, long uid, long now, List<Long> claimedOut) {
        List<AdTicket> pend = tickets.pending(uid);
        if (pend.isEmpty()) return List.of();
        GameState.Ad ad = ensure(g);
        rollAdDay(ad, now);
        List<Map<String, Object>> granted = new ArrayList<>();
        for (AdTicket t : pend) {
            if (granted.size() >= SETTLE_BATCH) break;
            if (!tickets.markSettled(t.getId())) continue;      // 并发下已被别的请求结算
            if (claimedOut != null) claimedOut.add(t.getId());
            String text = applyReward(g, t.getReward(), nz(t.getAmount()), null, now);
            ad.points += nz(t.getPoints());
            ad.total += 1;
            ad.addCount(t.getKind(), 1);
            ad.lastAt.put(t.getKind(), now);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("ticket", t.getTicket());            // 客户端靠它认出"我刚看的那一段到账了"
            one.put("kind", t.getKind());
            one.put("reward", t.getReward());
            one.put("amount", nz(t.getAmount()));
            one.put("points", nz(t.getPoints()));
            one.put("text", text);
            granted.add(one);
        }
        return granted;
    }

    /**
     * 一帧的存档没能落盘 ⇒ 把这一帧里结算掉的工单退回待结算，等奖励在下一次取帧时重新到账。
     * 由 GameService 在写盘冲突／失败时调用；不这样做的话"看完广告"和"拿到奖励"之间就断在半路上。
     */
    void requeue(long ticketId) {
        if (tickets.requeueSettled(ticketId))
            log.warn("这一帧存档没落盘，已结算的广告工单退回待结算、下次取帧重发 id={}", ticketId);
    }

    /* ================= 4. 积分兑换（取代钻石商店） ================= */
    Map<String, Object> exchange(GameState g, ContentRegistry.Snapshot s, String id, long now) {
        Content.AdConfig cfg = s.config.adOr();
        if (!cfg.on()) return res("ok", false, "msg", "广告中心暂未开放");
        Content.AdUnlock u = cfg.unlock(id);
        if (u == null) return res("ok", false, "msg", "没有这个兑换项");
        GameState.Ad ad = ensure(g);
        rollAdDay(ad, now);
        int done = ad.redeemed.getOrDefault(u.id(), 0);
        if (u.onceOr() && done > 0) return res("ok", false, "msg", "已经兑换过了");
        if (ownedAlready(g, u.reward(), u.target())) return res("ok", false, "msg", "已经拥有，无需再兑");
        if (ad.points < u.costOr()) return res("ok", false, "msg", "积分不足，还差 " + (u.costOr() - ad.points));
        ad.points -= u.costOr();
        ad.redeemed.put(u.id(), done + 1);
        String text = applyReward(g, u.reward(), u.amountOr(), u.target(), now);
        return res("ok", true, "id", u.id(), "points", ad.points, "text", text);
    }

    private static boolean ownedAlready(GameState g, String reward, String target) {
        if ("pack_el".equals(reward)) return g.packs.el;
        if ("skin".equals(reward)) return target != null && g.skins.owned.contains(target);
        return false;
    }

    /* ================= 5. 读：给广告中心渲染 ================= */

    /** 整帧视图：广告位余额/冷却、兑换目录、积分与今日余量。数值全部来自服务端，客户端只负责画。 */
    Map<String, Object> view(GameState g, ContentRegistry.Snapshot s, long uid, long now, List<Map<String, Object>> granted) {
        Content.AdConfig cfg = s.config.adOr();
        GameState.Ad ad = ensure(g);
        rollAdDay(ad, now);

        List<Map<String, Object>> slots = new ArrayList<>();
        for (Content.AdSlot sl : cfg.slotsOr()) {
            if (sl.kind() == null || sl.kind().isBlank() || !Content.AdConfig.knownReward(sl.reward())) continue;
            int left = Math.max(0, sl.dailyOr() - ad.dayCount(sl.kind()));
            long cd = 0;
            long lastAt = ad.lastAt.getOrDefault(sl.kind(), 0L);
            if (sl.cooldownOr() > 0) cd = Math.max(0, sl.cooldownOr() * 1000L - (now - lastAt));
            if (sl.dailyOr() <= 0) continue;                     // 下线位（daily=0）不展示，省得玩家点了才知道不行
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", sl.kind());
            m.put("zh", sl.zh());
            m.put("desc", sl.desc() == null ? "" : sl.desc());
            m.put("reward", sl.reward());
            m.put("amount", sl.amountOr());
            m.put("daily", sl.dailyOr());
            m.put("left", left);
            m.put("cooldownMs", cd);
            m.put("rewardText", rewardText(sl.reward(), sl.amountOr(), null));
            m.put("ready", left > 0 && cd == 0);
            slots.add(m);
        }

        List<Map<String, Object>> unlocks = new ArrayList<>();
        for (Content.AdUnlock u : cfg.unlocksOr()) {
            if (u.id() == null || u.id().isBlank() || !Content.AdConfig.knownReward(u.reward())) continue;
            int done = ad.redeemed.getOrDefault(u.id(), 0);
            boolean got = ownedAlready(g, u.reward(), u.target());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.id());
            m.put("zh", u.zh());
            m.put("desc", u.desc() == null ? "" : u.desc());
            m.put("cost", u.costOr());
            m.put("once", u.onceOr());
            m.put("done", u.onceOr() && (done > 0 || got));
            m.put("affordable", ad.points >= u.costOr() && !(u.onceOr() && (done > 0 || got)));
            m.put("rewardText", rewardText(u.reward(), u.amountOr(), u.target()));
            unlocks.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", cfg.on());
        out.put("ready", grantReady());
        out.put("devMode", devMode);
        out.put("minLevel", cfg.minLevelOr());
        out.put("locked", g.level < cfg.minLevelOr());
        out.put("points", ad.points);
        out.put("total", ad.total);
        out.put("revive", ad.revive);
        out.put("viewPoints", cfg.viewPointsOr());     // 玩家要能算出"看几段换得起"，所以这个数也得下发
        out.put("ticketTtlSec", cfg.ttlSecOr());
        out.put("leftToday", Math.max(0, cfg.dailyTotalOr() - ad.todayTotal()));
        out.put("dailyTotal", cfg.dailyTotalOr());
        out.put("slots", slots);
        out.put("unlocks", unlocks);
        out.put("granted", granted == null ? List.of() : granted);
        out.put("pending", tickets.pending(uid).size());
        return out;
    }

    /* ================= 存档侧的小工具 ================= */

    /** 旧存档没有 ad 这一段（Jackson 留 null），首次碰到就补一个空壳。 */
    static GameState.Ad ensure(GameState g) {
        if (g.ad == null) g.ad = new GameState.Ad();
        if (g.ad.perDay == null) g.ad.perDay = new LinkedHashMap<>();
        if (g.ad.lastAt == null) g.ad.lastAt = new LinkedHashMap<>();
        if (g.ad.redeemed == null) g.ad.redeemed = new LinkedHashMap<>();
        return g.ad;
    }

    /** 广告台账按 UTC 日滚动，与 {@code GameState.dayKey}／存档里的 daily 用同一把尺子。 */
    static void rollAdDay(GameState.Ad ad, long now) {
        String dk = GameState.dayKey(now);
        if (!dk.equals(ad.day)) {
            ad.day = dk;
            ad.perDay = new LinkedHashMap<>();
        }
    }

    /** 发奖励的唯一出口：settle 与 exchange 都走这里，数值封顶也在这里拦。 */
    private static String applyReward(GameState g, String reward, int amount, String target, long now) {
        String r = reward == null ? "" : reward;
        switch (r) {
            case "coins": {
                long gain = Math.max(0, amount);
                g.coins = Math.min(COIN_CAP, g.coins + gain);
                return "🪙" + gain;
            }
            case "diamonds": {
                long gain = Math.max(0, amount);
                g.diamonds = Math.min(DIAMOND_CAP, g.diamonds + gain);
                return "💎" + gain;
            }
            case "hints":
                g.hints += Math.max(0, amount);
                return "提示次数 +" + amount;
            case "coupon":
                // 双倍券沿用原任务系统的键，领取处（EconomyService.consumeDblCoupon）不用改
                g.daily.claimed.put("__adDaily", true);
                g.daily.claimed.put("__dblCoupon", true);
                return "今日双倍领取券";
            case "revive": {
                GameState.Ad ad = ensure(g);
                ad.revive += Math.max(0, amount);
                return "复活次数 +" + amount;
            }
            case "monthly_days": {
                long days = Math.max(0, amount);
                g.monthly.until = Math.max(now, g.monthly.until) + days * 86400000L;
                return "月卡 +" + days + " 天";
            }
            case "pack_el":
                g.packs.el = true;
                return "镧系·锕系已解锁";
            case "skin": {
                String sk = target == null ? "" : target;
                if (sk.isEmpty()) return "皮肤";
                if (!g.skins.owned.contains(sk)) g.skins.owned.add(sk);
                g.skins.cur = sk;
                return "皮肤·" + SKIN_ZH.getOrDefault(sk, sk);
            }
            default:
                log.warn("未知的广告奖励类型 {}（已忽略，检查 app_config.ad）", reward);
                return "奖励已到账";
        }
    }

    /** 奖励文案服务端出，免得两端各写一套说法对不上。 */
    static String rewardText(String reward, int amount, String target) {
        String r = reward == null ? "" : reward;
        switch (r) {
            case "coins": return "金币 🪙" + amount;
            case "diamonds": return "钻石 💎" + amount;
            case "hints": return "精灵提示 +" + amount;
            case "coupon": return "今日双倍领取券";
            case "revive": return "挑战复活 +" + amount + " 次";
            case "monthly_days": return "月卡时长 +" + amount + " 天";
            case "pack_el": return "镧系·锕系全解锁";
            case "skin": return "皮肤·" + SKIN_ZH.getOrDefault(target == null ? "" : target, target == null ? "?" : target);
            default: return r;
        }
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static LocalDateTime ldt(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
