package com.chemera.server.game;

import com.chemera.server.service.SaveService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/** 生产实现：GameState ↔ user_save.payload，经 SaveService 做校验/revision/裁剪。 */
@Service
public class DbGameStore implements GameStore {
    private final SaveService saves;
    private final ObjectMapper om;

    public DbGameStore(SaveService saves, ObjectMapper om) { this.saves = saves; this.om = om; }

    @Override
    public Optional<GameState> load(long uid) {
        Map<String, Object> got = saves.get(uid);
        if (!Boolean.TRUE.equals(got.get("exists"))) return Optional.empty();
        Object payload = got.get("payload");
        if (!(payload instanceof Map)) return Optional.empty();
        GameState g = om.convertValue(payload, GameState.class);
        return Optional.of(g);
    }

    @Override
    public long save(long uid, GameState g) {
        JsonNode node = om.valueToTree(g);
        Map<String, Object> r = saves.put(uid, node, null, true);   // 服务端权威：无条件写回
        Object rev = r.get("revision");
        return rev == null ? 0 : ((Number) rev).longValue();
    }
}
