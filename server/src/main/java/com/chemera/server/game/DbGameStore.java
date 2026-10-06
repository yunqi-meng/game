package com.chemera.server.game;

import com.chemera.server.service.SaveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/** 生产实现：GameState ↔ user_save.payload，经 SaveService 做校验/revision/裁剪。 */
@Service
public class DbGameStore implements GameStore {
    private static final Logger log = LoggerFactory.getLogger(DbGameStore.class);

    private final SaveService saves;
    private final ObjectMapper om;

    public DbGameStore(SaveService saves, ObjectMapper om) { this.saves = saves; this.om = om; }

    @Override
    public Optional<Frame> loadFrame(long uid) {
        Map<String, Object> got = saves.get(uid);
        if (!Boolean.TRUE.equals(got.get("exists"))) return Optional.empty();
        Object payload = got.get("payload");
        if (!(payload instanceof Map)) return Optional.empty();
        Map<?, ?> map = (Map<?, ?>) payload;
        // 版本位要在 convertValue 之前读：转换之后"没有 sv"和"sv 是 2"就分不出来了（缺字段变 null，
        // 而多余字段被 ignoreUnknown 丢掉），迁移分支依赖的正是这个区别。
        Object rawSv = map.get("sv");
        Integer from = (rawSv instanceof Number) ? ((Number) rawSv).intValue() : null;
        GameState g = om.convertValue(payload, GameState.class);
        SaveMigrations.apply(g, from);
        // 号跟帧一起出去：SaveService.get 本来就是同一条 SELECT 里的两列，天然原子，
        // 分成两次读给 GameService 用就会留下"别人的号配自己的旧帧"那个窗口。
        Object rev = got.get("revision");
        return Optional.of(new Frame(g, rev instanceof Number n ? n.longValue() : 0L));
    }

    /**
     * 交出去的是<b>对象本身</b>，不是 JSON 树（G8）。以前这一步是 {@code om.valueToTree(g)}，
     * 只为让 {@code SaveService.validate} 摸得到五个字段，摸完还要把整棵树再序列化一遍才落库——
     * 一份存档在被写进去之前多走了一整趟，而每条意图都要写一次盘。
     * 现在结构检查由 {@link GameState#defect()} 自己答（同五条不变量、同一套文案），
     * 版本位由写盘那道门统一盖（{@link GameState#stampSchemaVersion()}），
     * 序列化由 Jackson 一遍直出字符串，中间那棵用完就丢的树没有了。
     */
    @Override
    public long save(long uid, GameState g) {
        // 服务端权威：无条件写回
        Map<String, Object> r = saves.put(uid, g, null, true);
        return rev(r);
    }

    @Override
    public long saveCas(long uid, GameState g, long expected, String source) {
        Map<String, Object> r;
        try {
            r = saves.putCas(uid, g, expected, source);
        } catch (ConcurrencyFailureException e) {
            // 死锁或锁等待超时：InnoDB 已经把这一笔整个回滚，库里一个字都没变——
            // 这正是 {@link GameStore#CONFLICT} 的语义，调用方重读最新帧再重放同一个意图。
            // 让它继续往上冒等于把一次可恢复的撞号报成 500：玩家看见"操作失败"，
            // 服务端其实什么都没改，日志里还留一条没人看的堆栈。
            // 撞号是设计内的常态，所以这里 warn 不 error，并带上意图来源方便运营盯异常放量。
            log.warn("写回遇并发失败（按撞号重放处理）uid={} source={} base={}：{}", uid, source, expected, e.getMessage());
            return CONFLICT;
        }
        if (!Boolean.TRUE.equals(r.get("ok"))) return CONFLICT;
        return rev(r);
    }

    private static long rev(Map<String, Object> r) {
        Object v = r.get("revision");
        return v == null ? 0 : ((Number) v).longValue();
    }
}
