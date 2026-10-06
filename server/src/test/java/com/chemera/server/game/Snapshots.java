package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 测试用快照工厂：把内嵌的 {@code content-bundle.json} 走真实的 {@link ContentRegistry#build}
 * 路径解析成强类型快照，并允许按 cfg_key 覆盖 {@code app_config}。
 *
 * <p>刻意复用线上那条绑定路径（Jackson → {@code Content.Config}）而不是手工 new 记录：
 * G4 之后"改配置 ⇒ 判定跟着变"整条链路都建立在 JSON 形状与字段名对得上之上，测试绕过它就测不到这件事。
 */
final class Snapshots {

    static final ObjectMapper OM = new ObjectMapper();
    private static volatile Map<String, Object> BUNDLE;

    private Snapshots() {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bundle() throws Exception {
        if (BUNDLE != null) return BUNDLE;
        try (InputStream in = Snapshots.class.getResourceAsStream("/content-bundle.json")) {
            assertNotNull(in, "content-bundle.json 缺失");
            BUNDLE = OM.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        return BUNDLE;
    }

    @SuppressWarnings("unchecked")
    private static ContentRegistry.Snapshot build(Map<String, Object> cfg) throws Exception {
        Map<String, Object> b = bundle();
        long v = ((Number) b.getOrDefault("version", 0)).longValue();
        return new ContentRegistry(null, OM).build(v,
                (Map<String, Object>) b.getOrDefault("content", Map.of()), cfg);
    }

    private static Map<String, Object> config() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) bundle().getOrDefault("config", Map.of());
        return new LinkedHashMap<>(cfg);
    }

    /** 原样夹具：config 一个键都不动。 */
    static ContentRegistry.Snapshot base() throws Exception {
        return build(config());
    }

    /** 把某个 cfg_key 换成给定值后重建（模拟运营在后台改了这一项）。 */
    static ContentRegistry.Snapshot with(String cfgKey, Object value) throws Exception {
        Map<String, Object> cfg = config();
        cfg.put(cfgKey, value);
        return build(cfg);
    }

    /** 删掉某个 cfg_key 后重建（模拟迁移没跑 / 运营删键，测引擎兜底）。 */
    static ContentRegistry.Snapshot without(String cfgKey) throws Exception {
        Map<String, Object> cfg = config();
        cfg.remove(cfgKey);
        return build(cfg);
    }
}
