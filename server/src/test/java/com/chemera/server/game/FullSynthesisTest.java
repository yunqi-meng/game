package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全量合成回归（镜像 test/full-synthesis.js）：逐条驱动引擎，证明所有 143 条反应与 3 条工艺
 * 都能按其声明条件正常合成。真实模式 + 拉满安全 + 随机源 0.999，同签名兄弟反应可接受。
 */
class FullSynthesisTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final GameEngine e = new GameEngine();
    private final DoubleSupplier rng = () -> 0.999;

    @SuppressWarnings("unchecked")
    private ContentRegistry.Snapshot snap() throws Exception {
        Map<String, Object> bundle;
        try (InputStream in = getClass().getResourceAsStream("/content-bundle.json")) {
            assertNotNull(in, "content-bundle.json 缺失");
            bundle = OM.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        long v = ((Number) bundle.getOrDefault("version", 0)).longValue();
        return new ContentRegistry(null, OM).build(v,
                (Map<String, Object>) bundle.getOrDefault("content", Map.of()),
                (Map<String, Object>) bundle.getOrDefault("config", Map.of()));
    }

    private GameState readyState() {
        GameState g = GameState.fresh(System.currentTimeMillis());
        g.realMode = true;
        g.level = 20;
        g.lab.safety = 5;
        for (String k : List.of("lamp", "blowtorch", "electrolyzer", "centrifuge", "phmeter",
                "thermometer", "stand", "dropper", "spatula")) g.equipment.put(k, true);
        return g;
    }

    private static String sig(Content.Reaction r) {
        String temp = (r.conditions() == null || r.conditions().temp() == null || r.conditions().temp().isEmpty())
                ? "room" : r.conditions().temp();
        String cat = (r.conditions() == null || r.conditions().catalyst() == null) ? "" : r.conditions().catalyst();
        boolean elec = r.conditions() != null && Boolean.TRUE.equals(r.conditions().electrolysis());
        return temp + "|" + cat + "|" + elec + "|" + new TreeSet<>(r.reactantsOr().keySet());
    }

    @Test
    void allReactionsAndProcessesAreReachable() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = readyState();
        EngineCtx c = new EngineCtx();
        GameState.Bench b = e.cur(g, c);

        int intended = 0, sibling = 0;
        List<String> fail = new ArrayList<>();
        for (Content.Reaction r : s.reactions) {
            // setupBench：放置精确反应物 + 满足声明条件
            b.placed = new LinkedHashMap<>(r.reactantsOr());
            b.temp = r.cond().tempOrRoom();
            b.electrolysis = r.cond().elec();
            boolean needHeat = !"room".equals(b.temp);
            String vessel = "beaker";
            if (!r.instrumentOr().isEmpty()) {
                vessel = r.instrumentOr().get(0);
                for (String cand : r.instrumentOr()) {
                    Content.InstrumentDef iv = s.instrument(cand);
                    if (!(iv != null && iv.noHeatOr() && needHeat)) { vessel = cand; break; }
                }
            }
            b.vessel = vessel;
            String cat = r.cond().catalyst();
            if (cat != null) b.placed.merge(cat, 1, Math::max);

            boolean matched = e.matches(g, s, c).stream().anyMatch(x -> x.id().equals(r.id()));
            GameEngine.Result res = e.react(g, s, c, rng);
            boolean okKind = ("success".equals(res.kind) || "partial".equals(res.kind)) && !res.produced.isEmpty();
            String chosenId = res.reaction != null ? res.reaction.id() : (res.proc != null ? res.proc.id() : null);

            if (okKind && r.id().equals(chosenId)) intended++;
            else if (okKind && (matched || (res.reaction != null && sig(res.reaction).equals(sig(r))))) sibling++;
            else fail.add(r.id() + "[" + res.kind + "→" + chosenId + "]" + (matched ? "" : " 未命中matches"));
        }

        // 工艺
        int procOk = 0;
        List<String> procFail = new ArrayList<>();
        for (Content.ProcessDef p : s.processes) {
            b.placed = new LinkedHashMap<>(p.reactantsOr());
            b.temp = p.tempOrRoom();
            b.electrolysis = false;
            b.vessel = p.vessel();
            if (p.needEquip() != null) g.equipment.put(p.needEquip(), true);
            if (p.consume() != null) e.addItem(g, s, p.consume(), 3, 0, true);
            GameEngine.Result res = e.react(g, s, c, rng);
            if (("success".equals(res.kind) || "partial".equals(res.kind)) && res.proc != null
                    && p.id().equals(res.proc.id())) procOk++;
            else procFail.add(p.id() + "[" + res.kind + "→" + (res.proc != null ? res.proc.id() : "null") + "]");
        }

        // 元素完整性
        List<String> elBad = new ArrayList<>();
        for (Content.ElementDef el : s.elements) {
            if (el.id() == null || el.id().isEmpty() || el.zh() == null || el.z() == null) elBad.add(el.id());
        }

        System.out.printf("引擎全量合成：预期命中 %d，兄弟/组合命中 %d，失败 %d%n", intended, sibling, fail.size());
        System.out.printf("工艺：%d/%d 成功%n", procOk, s.processes.size());
        fail.forEach(f -> System.out.println("  ✗ " + f));
        procFail.forEach(f -> System.out.println("  ✗ 工艺 " + f));

        assertEquals(143, s.reactions.size(), "反应总数");
        assertTrue(fail.isEmpty(), "存在不可合成的反应: " + fail);
        assertEquals(s.processes.size(), procOk, "工艺全部可达: " + procFail);
        assertTrue(elBad.isEmpty(), "元素字段异常: " + elBad);
    }
}
