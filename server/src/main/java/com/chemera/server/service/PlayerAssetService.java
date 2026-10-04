package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.UserSave;
import com.chemera.server.mapper.SaveMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台给单个玩家增减金币/钻石。
 *
 * 刻意只在原 payload 上定点改这两个字段，而不是读成 GameState 再整体写回：
 * 存档里可能有客户端遗留字段（{@code GameState} 是 ignoreUnknown，读一遍就丢），
 * 定点改能保证"除了这笔增减，其它一个字节都不动"。
 */
@Service
public class PlayerAssetService {

    /** 单次增减的绝对值上限：宁可让运营分几次点，也不给一次手滑刷爆经济的机会。 */
    static final long MAX_STEP = 10_000_000L;
    /**
     * 调整后的封顶。量级按现有经济取值：最高充值档 98 元才给 1180 钻石，
     * 图鉴里程碑满打满算几万金币，撞上这两个数基本就是多打了几个零。
     */
    static final long COIN_CAP = 1_000_000_000L;
    static final long DIAMOND_CAP = 1_000_000L;

    private final SaveMapper saves;
    private final SaveService saveService;
    private final ObjectMapper om;

    public PlayerAssetService(SaveMapper saves, SaveService saveService, ObjectMapper om) {
        this.saves = saves; this.saveService = saveService; this.om = om;
    }

    public Map<String, Object> adjust(long uid, long coinDelta, long diamondDelta) {
        if (coinDelta == 0 && diamondDelta == 0) throw new BizException("金币与钻石都是 0，没有要调整的内容");
        checkStep("金币", coinDelta);
        checkStep("钻石", diamondDelta);

        UserSave s = saves.find(uid);
        if (s == null || s.getPayload() == null || s.getPayload().isBlank()) {
            throw new BizException("该玩家还没有云端存档（进过一次实验室才会生成），无法调整资产");
        }
        JsonNode parsed;
        try {
            parsed = om.readTree(s.getPayload());
        } catch (Exception e) {
            throw new BizException("存档不是合法 JSON，已阻止调整");
        }
        if (!parsed.isObject()) throw new BizException("存档结构非法，已阻止调整");
        ObjectNode payload = (ObjectNode) parsed;

        long coins = current(payload, "coins", "金币");
        long diamonds = current(payload, "diamonds", "钻石");
        long nextCoins = apply(coins, coinDelta, COIN_CAP, "金币");
        long nextDiamonds = apply(diamonds, diamondDelta, DIAMOND_CAP, "钻石");

        if (coinDelta != 0) payload.put("coins", nextCoins);
        if (diamondDelta != 0) payload.put("diamonds", nextDiamonds);
        // force=true：服务端是权威，后台写入不该和谁抢；source 让这条在存档历史里一眼认得出。
        saveService.put(uid, payload, null, true, "admin");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coinsBefore", coins);
        out.put("coinsAfter", nextCoins);
        out.put("diamondsBefore", diamonds);
        out.put("diamondsAfter", nextDiamonds);
        out.put("revision", saves.currentRevision(uid));
        return out;
    }

    private void checkStep(String what, long delta) {
        // 不用 Math.abs 判边界：Long.MIN_VALUE 取绝对值仍是负数，会漏过这道闸门。
        if (delta > MAX_STEP || delta < -MAX_STEP) {
            throw new BizException("单次" + what + "调整不超过 " + MAX_STEP + "，请分几次操作");
        }
    }

    private long current(ObjectNode payload, String field, String what) {
        JsonNode n = payload.get(field);
        if (n == null || n.isNull()) return 0;      // v1 老档可能没有钻石字段，按 0 起算
        if (!n.isNumber() || !n.canConvertToLong()) throw new BizException(what + "字段不是合法整数，已阻止调整");
        return n.asLong();
    }

    private long apply(long now, long delta, long cap, String what) {
        if (delta == 0) return now;
        long next = now + delta;
        if (next < 0) throw new BizException(what + "不足扣减量：当前 " + now + "，本次要扣 " + (-delta));
        if (next > cap) throw new BizException(what + "调整后会到 " + next + "，超过上限 " + cap);
        return next;
    }
}
