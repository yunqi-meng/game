package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.UserSave;
import com.chemera.server.entity.UserSaveRevision;
import com.chemera.server.mapper.SaveMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SaveService {
    /** 服务端权威意图写回的标记：只有它会被 {@link #historyWorth} 抽样，别的来源一律整条记。 */
    public static final String SOURCE_INTENT = "intent";

    /** 历史表往前留多少个版本号（不是多少行）；第 1 版永远另算，见 {@code SaveMapper.trimRevisionsBefore}。 */
    public static final int KEEP_REVISIONS = 30;

    /**
     * 强类型存档自带的那几条不变量（G8）。
     *
     * <p>为什么要有它：{@code DbGameStore} 写盘前先 {@code valueToTree(g)} 建一棵 JSON 树，
     * 只为让 {@link #validate(JsonNode)} 摸五个字段，再把树序列化一遍丢掉——同一份数据走了两遍，
     * 而每条意图都要写一次盘。对象自己就答得出这五条检查，那棵树从头到尾没有存在过哪怕一秒的理由。
     *
     * <p>它不是"跳过校验"的遮羞布：返回 null 表示结构没问题，非 null 就是给用户看的那句拒绝理由，
     * 与 {@link #validate} 用的是同一批不变量、同一套文案。外部输入（客户端上传、后台整档编辑）
     * 那条路依然走 {@code JsonNode} 版，因为那边的字段真的可能缺、可能类型不对。
     *
     * <p>第二个方法是<b>版本位</b>（A4）：结构版本由"谁写盘"决定，不该靠每个调用方记得去盖。
     * 以前只有 {@code DbGameStore} 那一处在写前盖，于是新增一个类型化写入方时，只要它忘了盖，
     * 落库的 payload 里 {@code sv} 就是 null——载入时会把这档当成"早于版本位的老存档"走补历史那支，
     * 而不是"刚由本机这版写出"。把盖章收到写盘这道门里，漏盖这件事就没有发生的余地了。
     */
    public interface Invariants {
        /** 结构不合法时返回拒绝理由，合法返回 {@code null}。 */
        String defect();

        /** 写盘前把当前服务端结构版本盖到自己身上（实现方就是那一个模型类）。 */
        void stampSchemaVersion();
    }

    private final SaveMapper saves;
    private final ObjectMapper om;
    private final long maxBytes;
    /**
     * 每多少个 revision 记一条存档历史。
     *
     * <p>以前每条意图都往 {@code user_save_revision} 塞一整个 payload 副本：玩家点一下"取帧"就
     * 是一次读 + 一次写 + 一份全量快照，热玩家一小时能刷出上千行，而历史页面上谁也翻不到那里。
     * 现在按号抽样（默认每 25 版一条），配合 {@link #putCas} 的"只读意图不落盘"，
     * 历史表回到"看得出他这段时间在干什么"的用途上，而不是"每个字节存了几百遍"。
     * 后台改档、重置、回滚、客户端上传这些低频高价值的写入不受抽样影响，见 {@link #historyWorth}。
     */
    private final long historyEvery;

    public SaveService(SaveMapper saves, ObjectMapper om,
                       @org.springframework.beans.factory.annotation.Value("${chemera.save.max-bytes:2097152}") long maxBytes,
                       @org.springframework.beans.factory.annotation.Value("${chemera.save.history-every:25}") long historyEvery) {
        this.saves = saves; this.om = om; this.maxBytes = maxBytes;
        this.historyEvery = historyEvery <= 0 ? 1 : historyEvery;
    }

    public Map<String, Object> get(long uid) {
        UserSave s = saves.find(uid);
        if (s == null) return Map.of("exists", false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exists", true);
        m.put("payload", parse(s.getPayload()));
        m.put("revision", s.getRevision());
        m.put("updatedAt", s.getUpdatedAt() == null ? 0 : s.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        return m;
    }

    /** 当前落库的 revision；没有存档行返回 0。{@link #putCas} 的期望值从这里来。 */
    public long revision(long uid) {
        Long cur = saves.currentRevision(uid);
        return cur == null ? 0 : cur;
    }

    /**
     * 服务端权威写回：只有库里仍然是 {@code base} 这一帧时才落盘。
     *
     * <p>为什么一定要带号：GameService 是"载入 → 结算 → 写回"，中间没有事务也没有锁。
     * 同一账号两台设备（手机 + 平板）各发各的意图时，两条链的写回会互相整帧覆盖，玩家看到的是
     * "我练了一半的东西没了"。带号写之后，抢输的那条只会被判冲突、重读重放，不会再抹掉对方。
     *
     * <p>建号那句只在 {@code base == 0} 时试，这不是收尾美化：并发跑回归时这里真死锁过。
     * {@code INSERT IGNORE} 撞重复键会给那一行留一个 <b>S 锁</b>，紧接着的 {@code casPut} 要的却是
     * 同一行的 <b>X 锁</b>；两个同时写回的请求各持一个 S 锁、又各等对方的 X，就是 InnoDB 最经典的
     * S→X 升级死锁（一方被整笔回滚，玩家看到的是 500）。读帧时既然带回了非零的号，库里就有行，
     * 这一次 INSERT 除了找锁什么也不干，省掉它顺便把死锁一起省掉。
     *
     * @return {@code {ok:true, revision}} 或 {@code {ok:false, conflict:true, revision}}（没写任何东西）
     */
    @Transactional
    public Map<String, Object> putCas(long uid, Object payload, long base, String source) {
        String json = prepare(payload);
        // 首帧（读帧时库里没人）：INSERT IGNORE 让并发建号的两个人里只有一个成功，
        // 另一个拿到 0 行、落到下面的 CAS 判冲突，重读重放即可。
        if (base == 0 && saves.insertFirst(uid, json) == 1) {
            record(uid, 1L, json, source);
            return Map.of("ok", true, "revision", 1L);
        }
        if (saves.casPut(uid, json, base) == 0) {
            return Map.of("ok", false, "conflict", true, "revision", revision(uid));
        }
        long newRev = base + 1;
        record(uid, newRev, json, source);
        return Map.of("ok", true, "revision", newRev);
    }

    /**
     * 落库前唯一的"能不能写"这道门：先验结构，再序列化，再看体积。
     *
     * <p>两种输入各有自己的验法，这不是历史包袱而是**输入来源不同**：{@code JsonNode} 来自客户端上传
     * 或后台整档编辑，字段可能缺、可能类型不对，只能按 JSON 摸；{@link Invariants} 来自服务端自己
     * 建模出来的存档对象，结构由类型系统保证，那五条不变量它自己报得出来，于是整棵中间树可以省掉。
     * 两条路共用同一个体积上限和同一套拒绝文案，不会出现"客户端被拦、后台写进去了"这种两套口径。
     */
    private String prepare(Object payload) {
        if (payload instanceof JsonNode n) {
            if (!n.isObject()) throw new BizException("存档格式非法");
            validate(n);
            return writeSafely(n);
        }
        if (payload instanceof Invariants iv) {
            String d = iv.defect();
            if (d != null) throw new BizException(d);
            iv.stampSchemaVersion();          // 合法的帧才配被盖章：被拒的那次不改动调用方手里的对象
            return writeSafely(payload);
        }
        throw new BizException("存档格式非法");
    }

    /**
     * 记一条存档历史。抽样只针对 {@link #SOURCE_INTENT}：意图写是玩家点出来的，量最大也最不值得逐条留；
     * 后台改档／重置／回滚／客户端上传是"会出事的那类写"，一条都不许漏。
     */
    private void record(long uid, long rev, String json, String source) {
        if (!historyWorth(source, rev)) return;
        UserSaveRevision r = new UserSaveRevision();
        r.setUserId(uid); r.setRevision(rev); r.setPayload(json); r.setSource(source);
        saves.addRevision(r);
        // 窗口从"我刚写到几号"往回算，不再让 DELETE 语句自己去表里求 MAX：
        // 写的一方本来就知道这个号，递进来就是一条走 idx_user_rev 的范围删除。
        saves.trimRevisionsBefore(uid, rev - KEEP_REVISIONS);
    }

    /**
     * 抽样判定：只有意图写按号抽样，其余来源整条记。
     * 包级可见是给单测直接钉这条策略用的——它决定的是"出事以后历史里翻不翻得到"，不该只靠肉眼读。
     *
     * <p>两个例外必须整条记，它们都不是"多存一行"的小事：
     * {@code rev<=1} 是账号的初始帧，后台那个【回滚到最早一版】按钮的语义就是退回这里，
     * 抽样把第 1 版抽掉，按钮就变成"退到不知道哪一版"；非意图来源（admin／upload／rollback／reset）
     * 本来一天也没几条，漏一条就是查不清谁改的。
     */
    boolean historyWorth(String source, long rev) {
        if (!SOURCE_INTENT.equals(source)) return true;
        return rev <= 1 || rev % historyEvery == 0;
    }


    @Transactional
    public Map<String, Object> put(long uid, Object payload, Long base, boolean force) {
        return put(uid, payload, base, force, "upload");
    }

    /** source 会进 user_save_revision，让玩家与运营在存档历史里分得清这次写入是客户端传的、后台改的还是回滚产生的。 */
    @Transactional
    public Map<String, Object> put(long uid, Object payload, Long base, boolean force, String source) {
        String json = prepare(payload);
        long curRev = 0;
        if (!force) {
            // 无条件写（服务端权威、后台改档）不需要这个基线：没人跟它抢，读了也只用不上。
            // 省下来的正是 G8 说的那"同事务里查两遍"的第一遍。
            Long cur = saves.currentRevision(uid);
            if (cur == null) {
                if (base == null) curRev = 0;
            } else {
                curRev = cur;
                if (base != null && base != curRev) {
                    UserSave s = saves.find(uid);
                    return Map.of("ok", false, "conflict", true, "revision", curRev,
                            "updatedAt", s == null || s.getUpdatedAt() == null ? 0
                                    : s.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                }
            }
        }
        saves.upsert(uid, json);
        // 号从写语句自己身上拿（LAST_INSERT_ID 会话变量），不再回表读一遍：
        // 回表那次如果正好有别人也写了，我们会把他的号当成自己的记进历史，对不上账。
        long newRev = saves.lastRevision();
        record(uid, newRev, json, source);
        return Map.of("ok", true, "revision", newRev, "updatedAt", System.currentTimeMillis());
    }

    /**
     * 后台【历史】列表：只有元信息，不带存档原文（G5）。
     * 回滚要的那一份由 {@link #rollback} 自己按版本号去取，两边不必都搬 50 份 payload。
     */
    public List<Map<String, Object>> revisionMeta(long uid) { return saves.listRevisionMeta(uid); }

    @Transactional
    public void rollback(long uid, long rev, String admin) {
        UserSaveRevision r = saves.findRevision(uid, rev);
        if (r == null) throw BizException.notFound("存档版本");
        saves.upsert(uid, r.getPayload());
        UserSaveRevision nr = new UserSaveRevision();
        nr.setUserId(uid); nr.setRevision(saves.lastRevision());   // 号跟着刚才那次 upsert 拿，不回表
        nr.setPayload(r.getPayload()); nr.setSource("rollback");
        saves.addRevision(nr);
    }

    private Object parse(String json) {
        try { return om.readValue(json, Object.class); } catch (Exception e) { return json; }
    }

    public void validate(JsonNode d) {
        int v = d.path("v").asInt(0);
        if (v != 1 && v != 2) throw new BizException("存档版本字段非法");
        if (!d.path("coins").isNumber() || d.path("coins").asDouble() < 0) throw new BizException("coins 非法");
        if (!d.path("level").isNumber() || d.path("level").asInt() < 1) throw new BizException("level 非法");
        if (!d.path("bag").isObject() || !d.path("discovered").isObject()) throw new BizException("bag/discovered 结构非法");
    }

    private String writeSafely(Object payload) {
        try {
            String json = om.writeValueAsString(payload);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes)
                throw new BizException("存档超过大小限制");
            return json;
        } catch (BizException b) { throw b; }
        catch (Exception e) { throw new BizException("存档序列化失败"); }
    }
}
