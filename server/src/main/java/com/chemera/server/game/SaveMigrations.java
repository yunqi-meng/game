package com.chemera.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 存档结构迁移（A4 的另一半）。
 *
 * <p>{@link GameState} 带着 {@code ignoreUnknown=true}，多余字段会被安静丢掉；缺字段同样安静——
 * 读进来就是 Java 默认值。也就是说"把 {@code ad.points} 改名成 {@code ad.credit}"这种改动上线后，
 * 老存档不会报错，只会在玩家下一次登录时发现自己攒的积分不见了。这类事故不可回溯，唯一的防线
 * 是在读盘时知道"这份 payload 是哪一版写的"，然后按版本补齐缺项。
 *
 * <p>纪律：任何字段改名、搬家、语义变化，都要在这里加一条 {@code case}，并把
 * {@link GameState#SCHEMA_VERSION} +1。加不了迁移的那次改动（比如删掉一个还没有等价物的字段）
 * 就不该上线——这也是版本位存在的意义：它让"漏写迁移"变成一件会被回归当场抓住的事。
 */
public final class SaveMigrations {

    private static final Logger log = LoggerFactory.getLogger(SaveMigrations.class);

    private SaveMigrations() {}

    /**
     * 把 {@code g} 从 {@code from} 版结构升到当前版。
     *
     * @param from payload 里的 {@code sv}；null 表示"早于版本位"，按最小版本处理
     * @throws com.chemera.server.common.BizException 存档版本比本机服务端还新（回滚/灰度串台），拒绝载入
     */
    public static void apply(GameState g, Integer from) {
        int cur = GameState.SCHEMA_VERSION;
        int v = from == null ? 0 : from;
        if (v > cur) {
            // 只有回滚或灰度时会走到这里。此时任何"按本机理解写回去"的动作都会把新版本的结构抹平，
            // 所以必须在这里停下：报一次可诊断的错，好过静默毁掉一份存档。
            throw com.chemera.server.common.BizException.forbidden(
                    "这份存档由更服务端的版本写入（sv=" + v + "，本机只认到 " + cur + "），已拒绝载入以免损坏进度；请联系客服");
        }
        if (v == cur) { g.sv = cur; return; }
        if (v <= 2) {
            // 第 2 版及更早：激励视频那套（积分、每日余量、冷却、已兑换）还不存在于存档里。
            // 以前是靠 AdService 每次派发时顺手 ensure，等于"每个读盘路径都得记得补"；
            // 记漏一次就是玩家看广告拿到工单、结算时却找不到落点。现在钉在读盘这一刻，补一次就够。
            AdService.ensure(g);
        }
        // 未来加 case 的写法：
        //   if (v <= 4) { g.newField = g.oldField; g.oldField = null; }
        g.sv = cur;
        log.debug("存档迁移 {} -> {}", v, cur);
    }
}
