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
    private final SaveMapper saves;
    private final ObjectMapper om;
    private final long maxBytes;

    public SaveService(SaveMapper saves, ObjectMapper om,
                       @org.springframework.beans.factory.annotation.Value("${chemera.save.max-bytes:2097152}") long maxBytes) {
        this.saves = saves; this.om = om; this.maxBytes = maxBytes;
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

    /** 冲突协议：base 为客户端持有的 revision；与服务端不一致且非 force 时返回冲突。 */
    @Transactional
    public Map<String, Object> put(long uid, JsonNode payload, Long base, boolean force) {
        return put(uid, payload, base, force, "upload");
    }

    /** source 会进 user_save_revision，让玩家与运营在存档历史里分得清这次写入是客户端传的、后台改的还是回滚产生的。 */
    @Transactional
    public Map<String, Object> put(long uid, JsonNode payload, Long base, boolean force, String source) {
        if (payload == null || !payload.isObject()) throw new BizException("存档格式非法");
        validate(payload);
        String json = writeSafely(payload);
        Long cur = saves.currentRevision(uid);
        long curRev = cur == null ? 0 : cur;
        if (!force && cur != null && base != null && base != curRev) {
            UserSave s = saves.find(uid);
            return Map.of("ok", false, "conflict", true, "revision", curRev,
                    "updatedAt", s.getUpdatedAt() == null ? 0 : s.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        saves.upsert(uid, json);
        long newRev = saves.currentRevision(uid);
        UserSaveRevision r = new UserSaveRevision();
        r.setUserId(uid); r.setRevision(newRev); r.setPayload(json); r.setSource(source);
        saves.addRevision(r);
        saves.trimRevisions(uid);
        return Map.of("ok", true, "revision", newRev, "updatedAt", System.currentTimeMillis());
    }

    public List<UserSaveRevision> revisions(long uid) { return saves.listRevisions(uid); }

    @Transactional
    public void rollback(long uid, long rev, String admin) {
        UserSaveRevision r = saves.findRevision(uid, rev);
        if (r == null) throw BizException.notFound("存档版本");
        saves.upsert(uid, r.getPayload());
        UserSaveRevision nr = new UserSaveRevision();
        nr.setUserId(uid); nr.setRevision(saves.currentRevision(uid));
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

    private String writeSafely(JsonNode payload) {
        try {
            String json = om.writeValueAsString(payload);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes)
                throw new BizException("存档超过大小限制");
            return json;
        } catch (BizException b) { throw b; }
        catch (Exception e) { throw new BizException("存档序列化失败"); }
    }
}
