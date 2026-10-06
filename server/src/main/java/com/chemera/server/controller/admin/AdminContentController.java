package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.common.Expect;
import com.chemera.server.common.Page;
import com.chemera.server.entity.ContentItem;
import com.chemera.server.entity.ContentRevision;
import com.chemera.server.game.AchievementRule;
import com.chemera.server.game.Content;
import com.chemera.server.game.ContentRegistry;
import com.chemera.server.game.ContentSchema;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.ContentHealthService;
import com.chemera.server.service.ContentRevisionService;
import com.chemera.server.service.ContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/content")
public class AdminContentController {
    private final ContentMapper content;
    private final AdminSupport support;
    private final AuditService audit;
    private final ObjectMapper om;
    private final ContentRegistry registry;
    private final ContentService contentService;
    private final ContentRevisionService revisions;
    private final ContentHealthService health;

    public AdminContentController(ContentMapper content, AdminSupport support, AuditService audit,
                                  ObjectMapper om, ContentRegistry registry, ContentService contentService,
                                  ContentRevisionService revisions, ContentHealthService health) {
        this.content = content; this.support = support; this.audit = audit; this.om = om;
        this.registry = registry; this.contentService = contentService; this.revisions = revisions;
        this.health = health;
    }

    @GetMapping("/types")
    public ApiResponse<List<String>> types() { return ApiResponse.ok(content.types()); }

    /**
     * 内容列表：{@code {rows,total}} 是后台分页列表的统一形状（见 {@link Page}），
     * {@code total} 是"这个筛选条件下库里有几行"，与本页挑了几行无关。
     */
    @GetMapping("/items")
    public ApiResponse<Page<Map<String, Object>>> list(@RequestParam String type,
                                                       @RequestParam(defaultValue = "") String q,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) Integer off) {
        int n = Page.size(size, 50);
        List<Map<String, Object>> rows = content.page(type, q, n, Page.off(off));
        return ApiResponse.ok(new Page<>(rows, content.count(type, q)));
    }

    @GetMapping("/item")
    public ApiResponse<Object> get(@RequestParam String type, @RequestParam String id) {
        ContentItem it = content.get(type, id);
        if (it == null) throw BizException.notFound("内容项");
        try { return ApiResponse.ok(om.readValue(it.getData(), Object.class)); }
        catch (Exception e) { return ApiResponse.ok(it.getData()); }
    }

    /* ---------------- 类型化表单驱动：schema + 引用/枚举下拉数据源 ---------------- */

    /** 13 类内容的字段描述符（含 kind/required/options/ref/sub），供管理 SPA 动态渲染表单。 */
    @GetMapping("/schema")
    public ApiResponse<Map<String, List<ContentSchema.Field>>> schema() {
        return ApiResponse.ok(ContentSchema.SCHEMAS);
    }

    /** 引用与枚举下拉数据源：闭集枚举原样下发，引用目标（物质/仪器/容器/工艺/元素）取当前快照，附带中文名标签。 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        ContentRegistry.Snapshot s = registry.current();
        List<Map<String, Object>> subs = new ArrayList<>();
        s.substances().values().stream()
                .sorted(java.util.Comparator.comparing(Content.Substance::id))
                .forEach(x -> subs.add(opt(x.id(), subLabel(x))));
        List<Map<String, Object>> instrs = new ArrayList<>();
        s.instruments.stream().sorted(java.util.Comparator.comparing(Content.InstrumentDef::id))
                .forEach(i -> instrs.add(opt(i.id(), i.zh() + " · " + i.id())));
        List<Map<String, Object>> vessels = instrs.stream()
                .filter(o -> {
                    Content.InstrumentDef d = s.instrument(String.valueOf(o.get("value")));
                    return d != null && d.isVessel();
                }).toList();
        List<Map<String, Object>> procs = new ArrayList<>();
        s.processes.stream().sorted(java.util.Comparator.comparing(Content.ProcessDef::id))
                .forEach(p -> procs.add(opt(p.id(), p.zh() + " · " + p.id())));
        List<Map<String, Object>> elems = new ArrayList<>();
        s.elements.stream().sorted(java.util.Comparator.comparing(Content.ElementDef::id))
                .forEach(e -> elems.add(opt(e.id(), e.zh() + " (" + e.symbol() + ") · " + e.id())));

        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("substance", subs);
        refs.put("instrument", instrs);
        refs.put("vessel", vessels);
        refs.put("process", procs);
        refs.put("element", elems);

        Map<String, Object> enums = new LinkedHashMap<>();
        enums.put("temps", ContentSchema.TEMPS);
        enums.put("fx", ContentSchema.FX);
        enums.put("rxTypes", ContentSchema.RX_TYPES);
        enums.put("elementCat", ContentSchema.ELEMENT_CAT);
        enums.put("elementState", ContentSchema.ELEMENT_STATE);
        enums.put("compoundState", ContentSchema.COMPOUND_STATE);
        enums.put("instrKind", ContentSchema.INSTR_KIND);
        enums.put("instrCat", ContentSchema.INSTR_CAT);
        enums.put("quizGrade", ContentSchema.QUIZ_GRADE);
        enums.put("taskKeys", ContentSchema.TASK_KEYS);
        enums.put("proc", ContentSchema.PROC);
        enums.put("achMetric", ContentSchema.ACH_METRIC);
        enums.put("achOp", ContentSchema.ACH_OP);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refs", refs);
        out.put("enums", enums);
        // 成就指标/比较符的中文口径（G4）：面板下拉拿它做标签，提交出去的仍是机器名，
        // 因为闭集校验与引擎读的都是 AchievementRule 里那个名字。
        Map<String, Object> enumZh = new LinkedHashMap<>();
        for (AchievementRule.Metric mk : AchievementRule.Metric.values()) enumZh.put(mk.name(), mk.zh());
        for (AchievementRule.Op op : AchievementRule.Op.values()) enumZh.put(op.name(), op.zh());
        out.put("enumsZh", enumZh);
        out.put("version", s.version);
        return ApiResponse.ok(out);
    }

    private static Map<String, Object> opt(String value, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value); m.put("label", label);
        return m;
    }

    private static String subLabel(Content.Substance x) {
        String formula = x.formula() == null || x.formula().isEmpty() ? "" : " (" + x.formula() + ")";
        return x.zh() + formula + " · " + x.id();
    }

    /* ---------------- 内容体检：逐行按当前快照校验，汇总错误 ---------------- */

    /**
     * 体检所有启用行（即下发给客户端的内容）：字段校验 + 引用完整性为"问题"；缺年级等提示为"告警"，不影响 ok。
     *
     * <p>结果按 {@code content_version} 缓存（见 {@link ContentHealthService}），因为这一份扫描是整表读进
     * JVM 再逐行解析的，而面板每次打开都问一遍。{@code fresh=true} 强制重扫——面板上那个
     * 【重新体检】按钮走的就是它：一个只会回缓存的"重新体检"等于没有那个按钮。
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health(@RequestParam(defaultValue = "false") boolean fresh) {
        return ApiResponse.ok(health.report(fresh));
    }

    /* ---------------- 写入：接入 schema + 引用校验 + 乐观锁（H6-2） ---------------- */

    /**
     * 写一条内容。
     *
     * <p>{@code expect} 是这一行的版本号（列表里那份 {@code updatedAt} 原文，见 {@link Expect}）：
     * 两个运营同时改同一条反应时，后提交的那个不该静默盖掉前一个。缺省（不带 {@code expect}）保持
     * 覆盖写语义，给没有逐行版本号的批量写入（回填脚本、回归里的 {@code hput}）留路。
     *
     * <p>回体带上写完之后那一行的 {@code updatedAt}，面板据此把手上那份换掉：否则同一个对话框里
     * 连点两次保存，第二次会拿着第一次之前的版本号，把自己刚才那次改动判成"别人改过"。
     */
    @PutMapping("/item")
    public ApiResponse<Map<String, Object>> upsert(@RequestBody Map<String, Object> body,
                                                   @RequestParam(defaultValue = "false") boolean strict,
                                                   @RequestParam(required = false) String expect,
                                                   HttpServletRequest req) {
        String by = support.requireWriter(req);
        String type = str(body.get("type"));
        String id = str(body.get("data") == null ? null : ((Map<?, ?>) body.get("data")).get("id"));
        if (id == null) id = str(body.get("id"));
        if (type == null || id == null) throw new BizException("type 与 data.id 必填");
        Object data = body.get("data");

        if (strict) {
            Map<String, Object> node = asMap(data);
            ContentSchema.Sets sets = setsOf(registry.current());
            List<String> errs = ContentSchema.validate(type, node, sets);
            if (!errs.isEmpty())
                throw new BizException("内容校验未通过：" + String.join("；", errs));
        }

        ContentItem it = new ContentItem();
        it.setContentType(type); it.setItemId(id);
        it.setName(strOr(body.get("name"), autoName(data, id)));
        it.setSort(body.get("sort") == null ? 0 : ((Number) body.get("sort")).intValue());
        it.setEnabled(body.get("enabled") == null ? 1 : ((Number) body.get("enabled")).intValue());
        try { it.setData(om.writeValueAsString(data)); } catch (Exception e) { throw new BizException("data 序列化失败"); }
        // 顶掉之前先把旧行存进历史（G3）：旧行必须在写之前读，事后 content.get 读回来的已经是新行；
        // 条件（H6-2）与写入在 ContentRevisionService 的同一个事务里，冲突时快照一并回滚。
        revisions.saveOver(content.get(type, id), it, by, Expect.parse(expect));
        support.publishContent();
        audit.log(by, "content.upsert", type + ":" + id, Map.of("strict", strict, "expect", expect == null ? "" : expect), req);
        return ApiResponse.ok(stamped(content.get(type, id), type, id));
    }

    @DeleteMapping("/item")
    public ApiResponse<Void> delete(@RequestParam String type, @RequestParam String id,
                                    @RequestParam(required = false) String expect, HttpServletRequest req) {
        String by = support.requireWriter(req);
        revisions.deleteOver(content.get(type, id), by, Expect.parse(expect));
        support.publishContent();
        audit.log(by, "content.delete", type + ":" + id, null, req);
        return ApiResponse.ok();
    }

    @PostMapping("/toggle")
    public ApiResponse<Map<String, Object>> toggle(@RequestParam String type, @RequestParam String id,
                                                   @RequestParam int enabled,
                                                   @RequestParam(required = false) String expect,
                                                   HttpServletRequest req) {
        String by = support.requireWriter(req);
        revisions.toggleOver(content.get(type, id), enabled, by, Expect.parse(expect));
        support.publishContent();
        audit.log(by, "content.toggle", type + ":" + id, Map.of("enabled", enabled), req);
        // 面板要把开关那行的版本号换过来，否则下次再点这个开关会撞上自己
        return ApiResponse.ok(stamped(content.get(type, id), type, id));
    }

    /** 回体里那一小块：写完之后这一行的新时间戳，面板拿它替换手上的版本号。 */
    private static Map<String, Object> stamped(ContentItem now, String type, String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("id", id);
        out.put("updatedAt", now == null ? null : now.getUpdatedAt());
        return out;
    }

    /* ---------------- 历史与回滚（G3） ---------------- */

    /**
     * 【历史】抽屉：这一行被覆盖掉过哪些版本。返回的是元数据（谁、什么时候、因为哪个动作、多大），
     * 不含整份记录——抽屉只需要列表，回滚时服务端按号自取。
     */
    @GetMapping("/revisions")
    public ApiResponse<List<Map<String, Object>>> revisions(@RequestParam String type,
                                                            @RequestParam String id,
                                                            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(revisions.history(type, id, limit));
    }

    /**
     * 单行回滚：把 {@code rev} 那一版原样写回 {@code content_item}，并顶一次版本号。
     *
     * <p>回滚走的仍是 strict 校验，这是这条路径唯一真正需要的护栏：历史里躺着的可能是"当初为了先让它不报错
     * 而临时塞的坏版本"，不加校验的话回滚等于把同一次事故重新发布给玩家。唯一的例外是
     * {@code enabled=0} 的那一版——它本来就不下发（{@code allEnabled} 筛掉），把它退回去只是留档，
     * 校验一个玩家看不见的行只会让人退回不到自己想要的状态。
     */
    @PostMapping("/rollback")
    public ApiResponse<Map<String, Object>> rollback(@RequestParam long rev,
                                                     @RequestParam(required = false) String expect,
                                                     HttpServletRequest req) {
        String by = support.requireWriter(req);
        ContentRevision r = revisions.require(rev);
        ContentItem live = content.get(r.getContentType(), r.getItemId());

        if (Integer.valueOf(1).equals(r.getEnabled())) {
            Map<String, Object> node;
            try { node = om.readValue(r.getDataJson(), Map.class); }
            catch (Exception e) { throw new BizException("这一版的数据无法解析为 JSON，回滚被拒绝"); }
            List<String> errs = ContentSchema.validate(r.getContentType(), node, setsOf(registry.current()));
            if (!errs.isEmpty())
                throw new BizException("回滚被拒绝：这一版按当前校验不通过：" + String.join("；", errs));
        }

        revisions.restoreOver(live, r, by, Expect.parse(expect));
        support.publishContent();
        audit.log(by, "content.rollback", r.getContentType() + ":" + r.getItemId(),
                Map.of("rev", rev, "fromVersion", r.getVersion(), "restoredEnabled", r.getEnabled()), req);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", r.getContentType());
        out.put("id", r.getItemId());
        out.put("rev", rev);
        out.put("version", content.version());
        // 回滚写的就是 live 行，所以它也要把新时间戳交回去（面板据此换掉手上的版本号）
        ContentItem after = content.get(r.getContentType(), r.getItemId());
        out.put("updatedAt", after == null ? null : after.getUpdatedAt());
        return ApiResponse.ok(out);
    }

    @PostMapping("/publish")
    public ApiResponse<Map<String, Object>> publish(HttpServletRequest req) {
        String by = support.requireWriter(req);
        support.publishContent();
        audit.log(by, "content.publish", null, null, req);
        return ApiResponse.ok(Map.of("version", content.version()));
    }

    /* ---------------- 辅助 ---------------- */

    /** 存在域组装已收敛到 {@code ContentSchema.Sets.of}（体检/写入/单测同一套），这里只留一个调用点。 */
    private static ContentSchema.Sets setsOf(ContentRegistry.Snapshot s) {
        return ContentSchema.Sets.of(s);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String strOr(Object o, String dft) { return o == null ? dft : String.valueOf(o); }
    private static String autoName(Object data, String id) {
        if (data instanceof Map<?, ?> m) {
            for (String k : new String[]{"zh", "q", "name"}) { Object v = m.get(k); if (v != null) return String.valueOf(v); }
        }
        return id;
    }
}
