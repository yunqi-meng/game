package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 激励视频经济的服务端回归（无 DB）：签发闸门、SSV 回调验签与幂等、过期、结算、积分兑换、演示通道。
 * 这段代码是本作唯一的"发钱"路径，所以断言的重点不是"能拿到奖励"，而是"不该拿的时候一定拿不到"。
 */
class AdServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String KEY = "test-security-key";

    /* ---------------- 脚手架 ---------------- */

    /** 用给定 ad 配置造一个最小内容快照（其余内容表留空：AdService 只读 config.ad）。 */
    private static ContentRegistry.Snapshot snapWith(String adJson) throws Exception {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        if (adJson != null) cfg.put("ad", OM.readValue(adJson, new TypeReference<Map<String, Object>>() {}));
        return new ContentRegistry(null, OM).build(1L, Map.of(), cfg);
    }

    /** 库里还没有 ad 键时的形态：走 Content.AdConfig.DEFAULT，正好等于上线配置。 */
    private static ContentRegistry.Snapshot snapDefault() throws Exception {
        return snapWith(null);
    }

    private static AdService svc(MemAdTickets tickets, String key, boolean devMode) {
        return new AdService(tickets, key, "{transId}{key}", "lower", devMode, "space-1");
    }

    private static GameState player() {
        GameState g = GameState.fresh(System.currentTimeMillis());
        g.coins = 0; g.diamonds = 0; g.hints = 0; g.monthly.until = 0;
        g.level = 5;                       // 越过 minLevel 闸门，让每个用例只管自己要测的那一条
        return g;
    }

    /** 按 AdService 的模板算一个合法签名：{transId}{key} 的 SHA-256 小写十六进制。 */
    private static String sign(String transId, String key) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest((transId + key).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static Map<String, String> cb(String trans, String extra, String uid, String sign) {
        Map<String, String> p = new java.util.LinkedHashMap<>();
        p.put("trans_id", trans); p.put("extra", extra); p.put("user_id", uid);
        p.put("pid", "space-1"); p.put("sign", sign);
        return p;
    }

    /** 走一遍"签发 → 回调"，返回工单号；调用方随后 settle 就能看到到账。 */
    private static String rewardedTicket(AdService ads, GameState g, ContentRegistry.Snapshot s,
                                         long uid, String kind, long now) throws Exception {
        ads.nowClock = () -> now;                        // 让过期判定跟着测试时间轴走，而不是墙钟
        Map<String, Object> r = ads.request(g, s, uid, kind, now);
        assertEquals(true, r.get("ok"), "签发应成功：" + r);
        String ticket = String.valueOf(r.get("ticket"));
        String trans = "tx-" + kind + "-" + ticket;
        Map<String, Object> back = ads.callback(cb(trans, ticket, String.valueOf(uid), sign(trans, KEY)));
        assertEquals(true, back.get("ok"), "回调应验签通过：" + back);
        return ticket;
    }

    /* ---------------- 1. 签发闸门 ---------------- */

    @Test
    void requestIsRefusedWithoutASettlementKey() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, "", false);              // 线上形态：无口令、非演示
        assertFalse(ads.grantReady(), "口令没配且没开演示模式时不算就绪");
        Map<String, Object> r = ads.request(player(), snapDefault(), 1L, "boom", System.currentTimeMillis());
        assertEquals(false, r.get("ok"), "不签发，别让玩家的『看广告』点了没下文");
        assertTrue(t.all().isEmpty(), "被拒的请求不落工单");
    }

    @Test
    void everyGateRefusesAndSaysWhy() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();

        assertEquals(false, ads.request(player(), s, 1L, "nope", 1_000L).get("ok"), "目录外的 kind");

        GameState baby = player(); baby.level = 0;
        assertEquals(false, ads.request(baby, s, 2L, "boom", 1_000L).get("ok"), "低于 minLevel 不进广告中心");

        ContentRegistry.Snapshot off = snapWith("{\"enabled\":false}");
        assertEquals(false, ads.request(player(), off, 3L, "boom", 1_000L).get("ok"), "enabled=false 关停整个中心");

        ContentRegistry.Snapshot gone = snapWith("{\"slots\":[{\"kind\":\"boom\",\"reward\":\"coins\",\"amount\":1,\"daily\":0}]}");
        assertEquals(false, ads.request(player(), gone, 3L, "boom", 1_000L).get("ok"), "daily=0 等于该位下线");

        // 冷却 → 当日次数 → 同位只挂一张，逐条验
        long now = 10_000_000L;
        GameState g = player();
        rewardedTicket(ads, g, s, 4L, "boom", now);
        ads.settle(g, 4L, now);
        Map<String, Object> cool = ads.request(g, s, 4L, "boom", now + 1_000);
        assertEquals(false, cool.get("ok"), "boom 冷却 300s");
        assertEquals(299_000L, ((Number) cool.get("cooldownMs")).longValue());
        rewardedTicket(ads, g, s, 4L, "boom", now + 400_000);
        ads.settle(g, 4L, now + 400_000);
        assertTrue(String.valueOf(ads.request(g, s, 4L, "boom", now + 800_000).get("msg")).contains("明天"),
                "boom 每日 2 次用完要说清");
        assertEquals(true, ads.request(g, s, 4L, "hint", now + 800_000).get("ok"), "别的位不受影响");
        Map<String, Object> hoard = ads.request(g, s, 4L, "hint", now + 800_001);
        assertEquals(false, hoard.get("ok"), "上一次没结算完就再点同位：拒绝，堵死囤券");
        assertNotNull(hoard.get("ticket"), "回执带上那张在挂的工单，客户端可继续等回调");
    }

    @Test
    void dailyTotalCapsEveryKindTogether() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapWith(
                "{\"dailyTotal\":2,\"slots\":[{\"kind\":\"boom\",\"reward\":\"coins\",\"amount\":1,\"daily\":9,\"cooldownSec\":0}]}");
        GameState g = player();
        long now = 5_000_000L;
        for (int i = 0; i < 2; i++) {
            rewardedTicket(ads, g, s, 1L, "boom", now + i * 10L);
            ads.settle(g, 1L, now + i * 10L);
        }
        assertEquals(false, ads.request(g, s, 1L, "boom", now + 5_000).get("ok"), "单位日次上限还没到，但总数上限已到");
        assertTrue(String.valueOf(ads.request(g, s, 1L, "boom", now + 5_000).get("msg")).contains("上限"));
    }

    @Test
    void malformedRewardStringsAreSkippedNotFatal() throws Exception {
        // 运营把 reward 写错、或整个漏填（null）：这一位该消失，但绝不能让不可变 Set.contains(null) 把整帧打崩
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapWith("{\"slots\":["
                + "{\"kind\":\"boom\",\"zh\":\"坏位\",\"reward\":\"gold_coins\",\"amount\":1,\"daily\":1},"
                + "{\"kind\":\"nil\",\"zh\":\"漏填\",\"amount\":1,\"daily\":1},"
                + "{\"kind\":\"hint\",\"zh\":\"好位\",\"reward\":\"hints\",\"amount\":2,\"daily\":3}]}");
        GameState g = player();
        long now = System.currentTimeMillis();
        assertFalse(Content.AdConfig.knownReward(null), "null 必须判成不认识");
        assertEquals(false, ads.request(g, s, 1L, "boom", now).get("ok"), "不认的 reward 等于位不存在");
        assertEquals(false, ads.request(g, s, 1L, "nil", now).get("ok"), "reward 为 null 也不签发");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) ads.view(g, s, 1L, now, List.of()).get("slots");
        assertEquals(1, slots.size(), "目录里只留合法位");
        assertEquals("hint", slots.get(0).get("kind"));
        assertEquals(true, ads.request(g, s, 1L, "hint", now).get("ok"), "合法位照常可用");
    }

    /* ---------------- 2. 回调验签与幂等 ---------------- */

    @Test
    void callbackRejectsForgedOrIncompleteRequests() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long now = System.currentTimeMillis();
        String ticket = String.valueOf(ads.request(g, s, 7L, "diamond", now).get("ticket"));

        assertEquals(false, ads.callback(Map.of("trans_id", "x", "extra", ticket)).get("ok"), "缺签名不放行");
        assertEquals(false, ads.callback(cb("", ticket, "7", sign("", KEY))).get("ok"), "缺交易号不放行");
        assertEquals(false, ads.callback(cb("tx-fake", ticket, "7", "deadbeef")).get("ok"), "签名不对不放行");
        assertEquals(false, ads.callback(cb("tx-fake", ticket, "8", sign("tx-fake", KEY))).get("ok"), "工单归属不符不放行");
        assertEquals(false, ads.callback(cb("tx-no", "0000000000000000000000000000000f", "7", sign("tx-no", KEY))).get("ok"),
                "凭空造的交易号找不到工单");

        assertTrue(ads.settle(g, 7L, now).isEmpty(), "以上都没置 rewarded，结算必须为空");
        assertEquals(0L, g.diamonds, "钻石分文未动");
        assertEquals(AdService.ISSUED, t.byTicket(ticket).getStatus(), "工单仍在等待合法回调");

        assertEquals(true, ads.callback(cb("tx-ok", ticket, "7", sign("tx-ok", KEY))).get("ok"));
        Map<String, Object> again = ads.callback(cb("tx-ok", ticket, "7", sign("tx-ok", KEY)));
        assertEquals(true, again.get("ok"), "重复回调仍回 ok，否则平台会一直重试");
        assertEquals("dup", again.get("code"));
        assertEquals(1, ads.settle(g, 7L, now).size(), "合法回调结算一次");
        assertEquals(8L, g.diamonds, "钻石补给到账");
        assertTrue(ads.settle(g, 7L, now).isEmpty(), "第二次结算不得再发（工单已 settled）");
    }

    /**
     * 验签口令缺失时，回调这条路径<b>一律</b>拒——连演示模式也不放开（迭代 4 的 F5）。
     * 回调不带玩家身份，"空口令 + dev-mode ⇒ 照单全收"等于把发奖接口开放给任何会 POST 的人；
     * 本机自证有它自己的门：{@code devGrant} 带玩家 JWT，工单归属查得出来。两条门的分工钉在这里。
     */
    @Test
    void callbackAlwaysRequiresASecurityKey() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService demo = svc(t, "", true);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long now = System.currentTimeMillis();
        String ticket = String.valueOf(demo.request(g, s, 9L, "boom", now).get("ticket"));

        Map<String, Object> r = demo.callback(cb("tx-any", ticket, "9", "anybody-could-type-this"));
        assertEquals(false, r.get("ok"), "空口令时任何回调都不放行");
        assertEquals("key", r.get("code"));
        assertEquals(AdService.ISSUED, t.byTicket(ticket).getStatus(), "工单不得被置成 rewarded");
        assertTrue(demo.settle(g, 9L, now).isEmpty(), "没回调就没有结算");
        assertEquals(0L, g.coins, "金币分文未动");

        // 同一张工单走演示通道仍然到账：关掉回调不等于关掉本机回归
        assertEquals(true, demo.devGrant(g, 9L, ticket).get("ok"), "devGrant 才是本机自证该走的那条");
        assertEquals(1, demo.settle(g, 9L, now).size(), "自证过的工单照常结算");
    }

    /**
     * 验签的可配面必须真的可配：Dirichlet 的文档只写了"trans_id 结合安全密钥做 SHA256"，
     * 拼接顺序与十六进制大小写都没写死，对接现场只能改配置、不能改代码。
     * 三条分支钉住：默认模板、大写回传、含 {pid}/{extra} 的自定义模板。
     */
    @Test
    void signTemplateAndHexAreOperatorConfigurable() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService lower = svc(t, KEY, false);
        String sha = hex("tx-1" + KEY);
        assertTrue(lower.verifySign(sha, "tx-1", "7", "space-1", "extra-1"), "默认模板 {transId}{key}：小写签名通过");
        assertTrue(lower.verifySign(sha.toUpperCase(java.util.Locale.ROOT), "tx-1", "7", "space-1", "extra-1"),
                "平台回传大写也不该误杀：比对按大小写无关归一");
        assertFalse(lower.verifySign("  ", "tx-1", "7", "space-1", "extra-1"), "空签名直接拒");

        AdService upper = new AdService(t, KEY, "{transId}{key}", "upper", false, "space-1");
        assertTrue(upper.verifySign(sha.toUpperCase(java.util.Locale.ROOT), "tx-1", "7", "p", "e"),
                "sign-hex=upper 时大写签名必须验得过（先前写成两边不同case，运营一切就永不通）");

        AdService custom = new AdService(t, KEY, "{pid}|{extra}|{transId}|{key}", "lower", false, "space-1");
        assertTrue(custom.verifySign(hex("p1|tk1|tx1|" + KEY), "tx1", "7", "p1", "tk1"), "自定义模板按占位符拼好后验签");
        assertFalse(custom.verifySign(sha, "tx-1", "7", "p1", "tk1"), "模板一改，旧算法的签名就不再有效");
    }

    private static String hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    void expiredTicketCannotBeRewarded() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapWith(
                "{\"ticketTtlSec\":30,\"slots\":[{\"kind\":\"boom\",\"reward\":\"coins\",\"amount\":5,\"daily\":2,\"cooldownSec\":0}]}");
        GameState g = player();
        long past = System.currentTimeMillis() - 60_000L;          // 一张 60 秒前签发的工单：30s 有效期早过了
        String ticket = String.valueOf(ads.request(g, s, 1L, "boom", past).get("ticket"));
        Map<String, Object> late = ads.callback(cb("tx-late", ticket, "1", sign("tx-late", KEY)));
        assertEquals(false, late.get("ok"), "超时回调不发奖");
        assertEquals("expired", late.get("code"));
        assertEquals(AdService.EXPIRED, t.byTicket(ticket).getStatus());
        assertEquals(0L, g.coins);
        assertEquals(true, ads.request(g, s, 1L, "boom", System.currentTimeMillis()).get("ok"), "过期工单不占坑，玩家可以再领");
    }

    @Test
    void concurrentSettlePaysOnce() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long now = System.currentTimeMillis();
        rewardedTicket(ads, g, s, 1L, "boom", now);
        t.refuseTransition = true;                       // 模拟别的请求抢先把这行置 settled
        assertTrue(ads.settle(g, 1L, now).isEmpty(), "抢输的一方不得再发一遍");
        assertEquals(0L, g.coins);
        assertEquals(0, g.ad.total);
        t.refuseTransition = false;
        assertEquals(1, ads.settle(g, 1L, now).size(), "条件更新成功才入账");
        assertEquals(500L, g.coins, "事故慰问金到账");
    }

    /* ---------------- 3. 结算：发奖与台账 ---------------- */

    /**
     * F1 的另一半，也是升级方案点名要的那条："markSettled 之后写盘失败，工单必须回到 pending"。
     *
     * <p>结算权靠 {@code rewarded→settled} 的条件更新抢，这一步不能退——不退的话并发两帧会各发一遍。
     * 但奖励先记在内存帧上，帧可能撞号作废，所以作废时必须把<b>这一帧抢到的</b>那几张退回 rewarded。
     * 退回本身也是条件更新（只动 settled 行），所以它不会凭空造出一笔钱：退回去的券必须再抢一次
     * 结算权才发得出去，而"一券一发"始终由 {@code markSettled} 那句条件 UPDATE 兜着。
     */
    @Test
    void aRewardOnADiscardedFrameGoesBackToPending() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        long now = System.currentTimeMillis();
        String ticket = rewardedTicket(ads, player(), s, 1L, "boom", now);

        GameState frame = player();
        List<Long> claimed = new java.util.ArrayList<>();
        assertEquals(1, ads.settle(frame, 1L, now, claimed).size(), "这一帧抢到结算权");
        assertEquals(1, claimed.size());
        assertEquals(AdService.SETTLED, t.byTicket(ticket).getStatus(), "抢到即 settled，别的并发帧发不出去");
        assertEquals(500L, frame.coins, "奖励先记在内存帧上");

        ads.requeue(claimed.get(0));                     // 这一帧没能落盘
        assertEquals(AdService.REWARDED, t.byTicket(ticket).getStatus(), "退回 pending，否则玩家白看一段广告");

        GameState replay = player();                     // 重放拿到的是库里的另一帧，不含上面那份改动
        assertEquals(0L, replay.coins);
        assertEquals(1, ads.settle(replay, 1L, now).size(), "下一次取帧把奖励补发出去");
        assertEquals(500L, replay.coins);
        assertEquals(AdService.SETTLED, t.byTicket(ticket).getStatus(), "补发后重新结算掉");
        assertTrue(ads.settle(replay, 1L, now).isEmpty(), "结算过的券不会再发第二遍");

        // 退回的作用域只有 settled：没结算过的券退不动，查无此行也不抛。
        // 这条边界很重要——它保证"作废帧退回工单"这个动作绝不会把一张没发过钱的券推进到账队列。
        GameState other = player();
        String t2 = rewardedTicket(ads, other, s, 2L, "boom", now + 1);
        long id2 = t.byTicket(t2).getId();
        ads.requeue(id2);
        assertEquals(AdService.REWARDED, t.byTicket(t2).getStatus(), "还没结算的工单不在退回范围内");
        ads.requeue(999_999L);                           // 查无此行：静默无操作
        assertEquals(1, ads.settle(other, 2L, now + 1).size(), "它照常只结算一次");
        assertEquals(AdService.SETTLED, t.byTicket(t2).getStatus());
    }

    @Test
    void settleAppliesEveryRewardType() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long now = System.currentTimeMillis();

        for (String kind : List.of("boom", "dbl", "diamond", "hint", "revive", "monthly")) {
            rewardedTicket(ads, g, s, 1L, kind, now);
        }
        List<Map<String, Object>> granted = ads.settle(g, 1L, now);
        assertEquals(6, granted.size(), "六个位各到账一次");
        assertEquals(500L, g.coins, "事故慰问金");
        assertEquals(8L, g.diamonds, "钻石补给");
        assertEquals(2, g.hints, "精灵提示");
        assertEquals(1, g.ad.revive, "复活次数入账，供 challenge.revive 消耗");
        assertEquals(6, g.ad.total, "累计观看次数");
        assertEquals(6, g.ad.points, "每次完成给 viewPoints=1");
        assertTrue(Boolean.TRUE.equals(g.daily.claimed.get("__dblCoupon")), "双倍券沿用任务系统的键，领取处不用改");
        assertTrue(g.monthly.until > now, "月卡时长往后加");
        assertEquals(6, g.ad.perDay.size(), "单位每日计数按 kind 分开记");
        assertEquals(false, ads.request(g, s, 1L, "dbl", now + 10_000).get("ok"), "结算即计入当日次数，不能靠囤券刷");
        assertEquals(6, ((Number) ads.view(g, s, 1L, now, List.of()).get("total")).intValue(), "视图里的累计次数与台账一致");
        Map<String, Object> vw = ads.view(g, s, 1L, now, List.of());
        assertEquals(1, ((Number) vw.get("viewPoints")).intValue(), "每段观看的积分要下发，玩家才算得出看几段换得起");
        assertEquals(900, ((Number) vw.get("ticketTtlSec")).intValue(), "工单有效期一并下发");
    }

    @Test
    void rollAdDayResetsPerDayCountersOnly() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long day1 = 1_700_000_000_000L;                 // 某个 UTC 日中午
        rewardedTicket(ads, g, s, 1L, "boom", day1);
        ads.settle(g, 1L, day1);
        assertEquals(1, g.ad.dayCount("boom"));
        int points = g.ad.points;
        long nextDay = day1 + 86_400_000L;
        assertEquals(true, ads.request(g, s, 1L, "boom", nextDay).get("ok"), "跨日重新计次");
        assertEquals(0, g.ad.dayCount("boom"), "当日计数已滚动");
        assertEquals(points, g.ad.points, "积分是累计资产，跨日不清零");
        assertEquals(1, g.ad.total);
    }

    /* ---------------- 4. 积分兑换（取代钻石商店） ---------------- */

    @Test
    void exchangeCostsPointsAndHonoursOnceOnly() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long now = System.currentTimeMillis();

        Map<String, Object> poor = ads.exchange(g, s, "elpack", now);
        assertEquals(false, poor.get("ok"), "零积分兑不动");
        assertTrue(String.valueOf(poor.get("msg")).contains("还差"), "要说清差多少，别只写失败");

        g.ad.points = 20;
        Map<String, Object> got = ads.exchange(g, s, "elpack", now);
        assertEquals(true, got.get("ok"), "兑换镧系·锕系礼包");
        assertTrue(g.packs.el);
        assertEquals(0, g.ad.points, "积分按标价扣干净");
        assertEquals(false, ads.exchange(g, s, "elpack", now).get("ok"), "once 项不能重兑");
        assertEquals(false, ads.exchange(g, s, "no-such", now).get("ok"), "目录外的 id 拒绝");
        assertEquals(false, ads.exchange(g, s, "skin_cyber", now).get("ok"), "没有积分了");
    }

    @Test
    void ownedItemsRefuseADuplicateExchange() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        g.ad.points = 100;
        g.packs.el = true;
        assertEquals(false, ads.exchange(g, s, "elpack", System.currentTimeMillis()).get("ok"), "已拥有就别再花钱");
        assertEquals(100, g.ad.points, "被拒的兑换不扣分");
        assertEquals(true, ads.exchange(g, s, "skin_cyber", System.currentTimeMillis()).get("ok"), "皮肤第一次可兑");
        assertTrue(g.skins.owned.contains("cyber"));
        assertEquals("cyber", g.skins.cur, "兑换即换上");
    }

    @Test
    void repeatableUnlockCanBeBoughtAgain() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long now = System.currentTimeMillis();
        g.ad.points = 60;
        assertEquals(true, ads.exchange(g, s, "monthly30", now).get("ok"));
        assertEquals(35, g.ad.points);
        assertTrue(g.monthly.until >= now + 30L * 86_400_000L, "30 天月卡时长入账");
        assertEquals(true, ads.exchange(g, s, "monthly30", now + 1).get("ok"), "非 once 项可重复兑换");
        assertEquals(10, g.ad.points);
        assertEquals(false, ads.exchange(g, s, "monthly30", now + 2).get("ok"), "积分不够就买不了");
    }

    /* ---------------- 5. 演示通道与只读视图 ---------------- */

    @Test
    void devGrantOnlyWorksWithDevModeAndOwnTicket() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService live = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long now = System.currentTimeMillis();
        String ticket = String.valueOf(live.request(g, s, 1L, "hint", now).get("ticket"));
        assertEquals(false, live.devGrant(g, 1L, ticket).get("ok"), "线上环境不认客户端自证");

        AdService demo = svc(t, "", true);
        assertTrue(demo.grantReady(), "演示模式算就绪，本机回归与浏览器预览才能走通");
        assertEquals(false, demo.devGrant(g, 2L, ticket).get("ok"), "拿别人的工单号没用");
        assertEquals(false, demo.devGrant(g, 1L, "").get("ok"), "空工单号");
        assertEquals(true, demo.devGrant(g, 1L, ticket).get("ok"), "演示模式：自己的工单可以自证");
        assertEquals(1, demo.settle(g, 1L, now).size(), "自证过的工单照常结算");
        assertEquals(2, g.hints);
    }

    @Test
    void viewReportsExactlyWhatTheClientMayShow() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, KEY, false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        long now = System.currentTimeMillis();
        Map<String, Object> v = ads.view(g, s, 1L, now, List.of());
        assertEquals(true, v.get("ready"), "口令到位就是就绪，前端据此决定按钮可点");
        assertEquals(false, v.get("devMode"));
        assertEquals(false, v.get("locked"));
        assertEquals(16, v.get("dailyTotal"));
        assertEquals(16, ((Number) v.get("leftToday")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) v.get("slots");
        assertEquals(6, slots.size());
        for (Map<String, Object> sl : slots) {
            assertNotNull(sl.get("rewardText"), "奖励文案由服务端出，两端不会说两套话");
            assertEquals(((Number) sl.get("daily")).intValue(), ((Number) sl.get("left")).intValue(), "新玩家余量=上限");
            assertEquals(0L, ((Number) sl.get("cooldownMs")).longValue(), "没看过就没有冷却");
            assertEquals(true, sl.get("ready"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unlocks = (List<Map<String, Object>>) v.get("unlocks");
        assertEquals(4, unlocks.size());
        for (Map<String, Object> u : unlocks) {
            assertEquals(false, u.get("done"), "一次都没兑过");
            assertEquals(0, ((Number) v.get("points")).intValue(), "零积分");
            assertEquals(false, u.get("affordable"), "零积分买不起任何东西");
        }
        assertEquals(0, ((Number) v.get("pending")).intValue(), "没有在挂工单");
    }

    @Test
    void viewFlagsNotReadyWithoutKeyAndShowsGranted() throws Exception {
        MemAdTickets t = new MemAdTickets();
        AdService ads = svc(t, "", false);
        ContentRegistry.Snapshot s = snapDefault();
        GameState g = player();
        Map<String, Object> v = ads.view(g, s, 1L, System.currentTimeMillis(),
                List.of(Map.of("kind", "boom", "reward", "coins", "amount", 500)));
        assertEquals(false, v.get("ready"), "没口令就诚实报未就绪，healthz 与前端都能据此提示");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> granted = (List<Map<String, Object>>) v.get("granted");
        assertEquals(1, granted.size());
        assertEquals("boom", granted.get(0).get("kind"));
    }
}
