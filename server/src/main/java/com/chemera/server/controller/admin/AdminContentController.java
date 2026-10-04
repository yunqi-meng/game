package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.entity.ContentItem;
import com.chemera.server.game.Content;
import com.chemera.server.game.ContentRegistry;
import com.chemera.server.game.ContentSchema;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.ContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/admin/api/content")
public class AdminContentController {
    private final ContentMapper content;
    private final AdminSupport support;
    private final AuditService audit;
    private final ObjectMapper om;
    private final ContentRegistry registry;
    private final ContentService contentService;

    public AdminContentController(ContentMapper content, AdminSupport support, AuditService audit,
                                  ObjectMapper om, ContentRegistry registry, ContentService contentService) {
        this.content = content; this.support = support; this.audit = audit; this.om = om;
        this.registry = registry; this.contentService = contentService;
    }

    @GetMapping("/types")
    public ApiResponse<List<String>> types() { return ApiResponse.ok(content.types()); }

    @GetMapping("/items")
    public ApiResponse<Map<String, Object>> list(@RequestParam String type,
                                                 @RequestParam(defaultValue = "") String q,
                                                 @RequestParam(defaultValue = "50") int size,
                                                 @RequestParam(defaultValue = "0") int off) {
        List<Map<String, Object>> rows = content.page(type, q, Math.min(size, 500), off);
        long total = content.count(type, q);
        return ApiResponse.ok(Map.of("rows", rows, "total", total));
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refs", refs);
        out.put("enums", enums);
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

    /** 体检所有启用行（即下发给客户端的内容）：字段校验 + 引用完整性为"问题"；缺年级等提示为"告警"，不影响 ok。 */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        ContentSchema.Sets sets = setsOf(registry.current());
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        int checked = 0;
        for (ContentItem it : content.allEnabled()) {
            checked++;
            Map<String, Object> data;
            try {
                data = om.readValue(it.getData(), Map.class);
            } catch (Exception e) {
                issues.add(issue(it.getContentType(), it.getItemId(), List.of("数据无法解析为 JSON")));
                continue;
            }
            List<String> errs = ContentSchema.validate(it.getContentType(), data, sets);
            if (!errs.isEmpty()) { issues.add(issue(it.getContentType(), it.getItemId(), errs)); continue; }
            if ("quiz".equals(it.getContentType()) && data.get("grade") == null)
                warnings.add(issue(it.getContentType(), it.getItemId(), List.of("建议补充年级(grade)标签")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", checked);
        out.put("issueCount", issues.size());
        out.put("warningCount", warnings.size());
        out.put("ok", issues.isEmpty());
        out.put("version", content.version());
        out.put("issues", issues);
        out.put("warnings", warnings);
        return ApiResponse.ok(out);
    }

    private static Map<String, Object> issue(String type, String id, List<String> errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type); m.put("id", id); m.put("errors", errors);
        return m;
    }

    /* ---------------- 写入：接入 schema + 引用校验 ---------------- */

    @PutMapping("/item")
    public ApiResponse<Void> upsert(@RequestBody Map<String, Object> body,
                                    @RequestParam(defaultValue = "false") boolean strict,
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
        it.setUpdatedBy(by);
        content.upsert(it);
        support.publishContent();
        audit.log(by, "content.upsert", type + ":" + id, Map.of("strict", strict), req);
        return ApiResponse.ok();
    }

    @DeleteMapping("/item")
    public ApiResponse<Void> delete(@RequestParam String type, @RequestParam String id, HttpServletRequest req) {
        String by = support.requireWriter(req);
        content.delete(type, id);
        support.publishContent();
        audit.log(by, "content.delete", type + ":" + id, null, req);
        return ApiResponse.ok();
    }

    @PostMapping("/toggle")
    public ApiResponse<Void> toggle(@RequestParam String type, @RequestParam String id,
                                    @RequestParam int enabled, HttpServletRequest req) {
        String by = support.requireWriter(req);
        content.setEnabled(type, id, enabled, by);
        support.publishContent();
        audit.log(by, "content.toggle", type + ":" + id, Map.of("enabled", enabled), req);
        return ApiResponse.ok();
    }

    @PostMapping("/publish")
    public ApiResponse<Map<String, Object>> publish(HttpServletRequest req) {
        String by = support.requireWriter(req);
        support.publishContent();
        audit.log(by, "content.publish", null, null, req);
        return ApiResponse.ok(Map.of("version", content.version()));
    }

    /* ---------------- 辅助 ---------------- */

    /** 从当前快照组装引用存在域：物质/仪器/容器(kind=vessel)/工艺/元素。 */
    private static ContentSchema.Sets setsOf(ContentRegistry.Snapshot s) {
        var substances = new TreeSet<>(s.substances().keySet());
        var instruments = new TreeSet<String>();
        var vessels = new TreeSet<String>();
        for (Content.InstrumentDef i : s.instruments) {
            instruments.add(i.id());
            if (i.isVessel()) vessels.add(i.id());
        }
        var processes = new TreeSet<String>();
        s.processes.forEach(p -> processes.add(p.id()));
        var elements = new TreeSet<String>();
        s.elements.forEach(e -> elements.add(e.id()));
        return new ContentSchema.Sets(substances, instruments, vessels, processes, elements);
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
