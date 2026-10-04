package com.chemera.server.game;

import com.chemera.server.service.ContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容注册表：把 ContentService 下发的 bundle（DB 派生的 Map）解析为强类型快照，按 content_version 缓存。
 * 引擎（GameEngine）只消费这里产出的不可变 Snapshot，不再直接读 Map。
 */
@Service
public class ContentRegistry {

    private final ContentService content;
    private final ObjectMapper om;

    private volatile long cachedVersion = Long.MIN_VALUE;
    private volatile Snapshot cached;

    public ContentRegistry(ContentService content, ObjectMapper om) {
        this.content = content;
        this.om = om;
    }

    /** 当前快照；content_version 变化时重建。 */
    @SuppressWarnings("unchecked")
    public Snapshot current() {
        Map<String, Object> bundle = content.bundle();
        long v = asLong(bundle.get("version"));
        Snapshot local = cached;
        if (v == cachedVersion && local != null) return local;
        synchronized (this) {
            if (v == cachedVersion && cached != null) return cached;
            Map<String, Object> c = (Map<String, Object>) bundle.getOrDefault("content", Map.of());
            Map<String, Object> cfgMap = (Map<String, Object>) bundle.getOrDefault("config", Map.of());
            Snapshot s = build(v, c, cfgMap);
            cached = s;
            cachedVersion = v;
            return s;
        }
    }

    public void invalidate() { cachedVersion = Long.MIN_VALUE; }

    /* ---------------- 构建（包级可见，便于无 DB 单元测试直接解析） ---------------- */
    Snapshot build(long version, Map<String, Object> c, Map<String, Object> cfgMap) {
        List<Content.Reaction> reactions = list(c.get("reaction"), Content.Reaction.class);
        List<Content.ElementDef> elements = list(c.get("element"), Content.ElementDef.class);
        List<Content.CompoundDef> compounds = list(c.get("compound"), Content.CompoundDef.class);
        List<Content.ConsumableDef> consumables = list(c.get("consumable"), Content.ConsumableDef.class);
        List<Content.InstrumentDef> instruments = list(c.get("instrument"), Content.InstrumentDef.class);
        List<Content.RoomDef> rooms = list(c.get("room"), Content.RoomDef.class);
        List<Content.ProcessDef> processes = list(c.get("process"), Content.ProcessDef.class);
        List<Content.DangerDef> dangers = list(c.get("danger"), Content.DangerDef.class);
        List<Content.AchievementDef> achievements = list(c.get("achievement"), Content.AchievementDef.class);
        List<Content.TaskDef> tasks = list(c.get("task"), Content.TaskDef.class);
        List<Content.NpcDef> npcs = list(c.get("npc"), Content.NpcDef.class);
        List<Content.ShopDef> shop = list(c.get("shop"), Content.ShopDef.class);
        List<Content.QuizDef> quizzes = list(c.get("quiz"), Content.QuizDef.class);

        Content.Config config = om.convertValue(cfgMap, Content.Config.class);
        return new Snapshot(version, reactions, elements, compounds, consumables, instruments, rooms,
                processes, dangers, achievements, tasks, npcs, shop, quizzes, config);
    }

    private <T> List<T> list(Object raw, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (raw instanceof List<?> arr) {
            for (Object node : arr) {
                if (node instanceof Map<?, ?> m) out.add(om.convertValue(m, type));
            }
        }
        return out;
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) { try { return Long.parseLong(s); } catch (NumberFormatException ignored) {} }
        return 0;
    }

    /* ---------------- 不可变快照 ---------------- */
    public static final class Snapshot {
        public final long version;
        public final List<Content.Reaction> reactions;
        public final List<Content.ElementDef> elements;
        public final List<Content.CompoundDef> compounds;
        public final List<Content.ConsumableDef> consumables;
        public final List<Content.InstrumentDef> instruments;
        public final List<Content.RoomDef> rooms;
        public final List<Content.ProcessDef> processes;
        public final List<Content.DangerDef> dangers;
        public final List<Content.AchievementDef> achievements;
        public final List<Content.TaskDef> tasks;
        public final List<Content.NpcDef> npcs;
        public final List<Content.ShopDef> shop;
        public final List<Content.QuizDef> quizzes;
        public final Content.Config config;

        private final Map<String, Content.Reaction> reactionById = new LinkedHashMap<>();
        private final Map<String, Content.InstrumentDef> instrumentById = new LinkedHashMap<>();
        private final Map<String, Content.RoomDef> roomById = new LinkedHashMap<>();
        private final Map<String, Content.Substance> substanceById = new LinkedHashMap<>();
        private final Map<String, Content.AchievementDef> achievementById = new LinkedHashMap<>();
        private final Map<String, Content.NpcDef> npcById = new LinkedHashMap<>();
        private final Map<String, Content.QuizDef> quizById = new LinkedHashMap<>();

        Snapshot(long version, List<Content.Reaction> reactions, List<Content.ElementDef> elements,
                 List<Content.CompoundDef> compounds, List<Content.ConsumableDef> consumables,
                 List<Content.InstrumentDef> instruments, List<Content.RoomDef> rooms,
                 List<Content.ProcessDef> processes, List<Content.DangerDef> dangers,
                 List<Content.AchievementDef> achievements, List<Content.TaskDef> tasks,
                 List<Content.NpcDef> npcs, List<Content.ShopDef> shop, List<Content.QuizDef> quizzes,
                 Content.Config config) {
            this.version = version;
            this.reactions = reactions;
            this.elements = elements;
            this.compounds = compounds;
            this.consumables = consumables;
            this.instruments = instruments;
            this.rooms = rooms;
            this.processes = processes;
            this.dangers = dangers;
            this.achievements = achievements;
            this.tasks = tasks;
            this.npcs = npcs;
            this.shop = shop;
            this.quizzes = quizzes;
            this.config = config;

            reactions.forEach(r -> reactionById.put(r.id(), r));
            instruments.forEach(i -> instrumentById.put(i.id(), i));
            rooms.forEach(r -> roomById.put(r.id(), r));
            achievements.forEach(a -> achievementById.put(a.id(), a));
            npcs.forEach(n -> npcById.put(n.id(), n));
            quizzes.forEach(q -> quizById.put(q.id(), q));
            buildSubstances();
        }

        /** 归一化物质表：元素 → 化合物 → 耗材 → 合成废渣 SLAG，与 state.js allSubs 一致。 */
        private void buildSubstances() {
            for (Content.ElementDef e : elements) {
                boolean haz = "halogen".equals(e.cat()) || "actinide".equals(e.cat()) || "alkali".equals(e.cat());
                substanceById.put(e.id(), new Content.Substance(e.id(), "element", e.zh(), e.symbol(), 0,
                        nz(e.price()), e.state(), e.color(), haz, List.of(e.id()), e.cat(), nz(e.z()), "", e.desc()));
            }
            for (Content.CompoundDef cc : compounds) {
                if (substanceById.containsKey(cc.id())) continue;
                substanceById.put(cc.id(), new Content.Substance(cc.id(), "compound", cc.zh(), cc.formula(),
                        nz(cc.level()), nz(cc.price()), cc.state(), cc.color(), Boolean.TRUE.equals(cc.hazard()),
                        cc.elements() == null ? List.of() : cc.elements(), "", 0, cc.uses(), cc.desc()));
            }
            for (Content.ConsumableDef cu : consumables) {
                if (substanceById.containsKey(cu.id())) continue;
                substanceById.put(cu.id(), new Content.Substance(cu.id(), "consumable", cu.zh(), "耗材", 0,
                        nz(cu.price()), "solid", "#78909c", false, List.of(), "", 0, "实验耗材", cu.desc()));
            }
            substanceById.putIfAbsent("SLAG", new Content.Substance("SLAG", "compound", "实验废渣", "?", 1,
                    5, "solid", "#8a8578", false, List.of(), "", 0, "低价回收", "反应失败的混合物。"));
        }

        private static int nz(Integer i) { return i == null ? 0 : i; }

        /* 查询 */
        public Content.Reaction reaction(String id) { return reactionById.get(id); }
        public Content.InstrumentDef instrument(String id) { return instrumentById.get(id); }
        public Content.RoomDef room(String id) { return roomById.getOrDefault(id, rooms.isEmpty() ? null : rooms.get(0)); }
        public Content.Substance substance(String id) { return substanceById.get(id); }
        public Map<String, Content.Substance> substances() { return substanceById; }
        public Content.AchievementDef achievement(String id) { return achievementById.get(id); }
        public Content.NpcDef npc(String id) { return npcById.get(id); }
        public Content.QuizDef quiz(String id) { return quizById.get(id); }
    }
}
