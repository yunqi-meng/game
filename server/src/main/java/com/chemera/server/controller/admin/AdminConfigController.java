package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.common.Expect;
import com.chemera.server.common.Page;
import com.chemera.server.entity.AppConfigRow;
import com.chemera.server.game.AdConfigValidator;
import com.chemera.server.game.ComplianceConfigValidator;
import com.chemera.server.game.ConfigSpec;
import com.chemera.server.game.EngineConfigValidator;
import com.chemera.server.mapper.ConfigMapper;
import com.chemera.server.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/config")
public class AdminConfigController {
    private final ConfigMapper config;
    private final AdminSupport support;
    private final AuditService audit;
    private final ObjectMapper om;

    public AdminConfigController(ConfigMapper config, AdminSupport support, AuditService audit, ObjectMapper om) {
        this.config = config; this.support = support; this.audit = audit; this.om = om;
    }

    /**
     * 配置列表（H6-3）：以前这里是整表回传、面板也就没有分页，页数与"到底有几个键"没人说得清。
     * 现在和【内容】【用户】【审核】同形（{@link Page}），并支持按键名/分类/备注搜。
     *
     * <p>键的<b>中文名</b>在 {@link ConfigSpec}（Java 那份说明书）里而不在库里，所以搜索不认中文名称——
     * 面板的搜索框据此写明"按键名、分类或备注"，不假装能搜名称。
     */
    @GetMapping
    public ApiResponse<Page<AppConfigRow>> list(@RequestParam(defaultValue = "") String q,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) Integer off) {
        return ApiResponse.ok(new Page<>(config.page(q, Page.size(size, 20), Page.off(off)), config.count(q)));
    }

    /**
     * 配置说明书（含"这项到底影不影响结算"）。给后台面板当文案用，
     * 未列在这里的键意味着引擎的 Config 记录里没有对应字段，改了不会被服务端读到。
     */
    @GetMapping("/spec")
    public ApiResponse<List<ConfigSpec>> spec() { return ApiResponse.ok(ConfigSpec.ALL); }

    /**
     * 广告目录的编辑说明书：reward 可选值、皮肤 id、各数值边界，全部由后端出。
     * 面板的下拉据此生成，这样"引擎认哪些奖励"在仓库里只有一份答案。
     */
    @GetMapping("/ad/schema")
    public ApiResponse<Map<String, Object>> adSchema() {
        Map<String, Object> schema = new java.util.LinkedHashMap<>(AdConfigValidator.schema());
        schema.put("defaults", AdConfigValidator.defaults(om)); // slots 缺省≠空数组，面板要能展开真正生效的那份
        return ApiResponse.ok(schema);
    }

    /**
     * 合规配置（{@code curfew} / {@code app_version}）的编辑说明书：字段清单、数值边界、内置默认窗口。
     * 面板据此生成控件，"引擎读哪些字段"在仓库里只留一份答案（同 {@link #adSchema()} 的理由）。
     */
    @GetMapping("/compliance/schema")
    public ApiResponse<Map<String, Object>> complianceSchema() {
        return ApiResponse.ok(ComplianceConfigValidator.schema());
    }

    @GetMapping("/get")    public ApiResponse<Object> get(@RequestParam String key) {
        AppConfigRow r = config.get(key);
        if (r == null) throw BizException.notFound("配置项");
        try { return ApiResponse.ok(om.readValue(r.getCfgValue(), Object.class)); }
        catch (Exception e) { return ApiResponse.ok(r.getCfgValue()); }
    }

    /**
     * 写配置。
     *
     * <p>{@code expect} 是这一行的版本号（列表里那份 {@code updatedAt} 原文，见 {@link Expect}）：
     * 配置面板是"拨一下就影响全体玩家"的地方，两个运营同时开着同一个键，后保存的那个不该静默盖掉前一个。
     * 不带 {@code expect} 时保持覆盖写——那是批量回填与既有脚本走的路（它们手上没有逐行版本号）。
     *
     * <p>{@code ad} 这一个键要先过 {@link AdConfigValidator}：广告目录是整条变现链里唯一由人手工填的表，
     * 而引擎对坏行的态度是静默跳过（reward 不认就 {@code continue}，字段拼错就被 Jackson 丢掉），
     * 所以"保存成功但玩家看完广告什么都不发"这种结果只能在这一步拦，不能指望运行期报错。
     *
     * <p>{@code curfew} / {@code app_version} / {@code analytics_enabled} 同理但方向相反：填坏了不会少发钱，
     * 而是把玩家关在门外、或让行为记录停止入库，且游戏内不会有任何报错回声
     * （见 {@link ComplianceConfigValidator}）。
     *
     * <p>{@code level_exp} / {@code bench_max_lines} / {@code accident} / {@code quiz_grade_mult}
     * 是 G4 刚从 Java 常数搬进库里的结算参数，填坏了一样没有回声（见 {@link EngineConfigValidator}）。
     *
     * <p>校验排在条件写之前：一个本来就该被拒的值，没必要再告诉运营"版本冲突"。
     */
    @PutMapping
    public ApiResponse<Map<String, Object>> upsert(@RequestBody Map<String, Object> body,
                                                   @RequestParam(required = false) String expect,
                                                   HttpServletRequest req) {
        String by = support.requireWriter(req);
        String key = String.valueOf(body.get("key"));
        if (key == null || "null".equals(key)) throw new BizException("key 必填");
        JsonNode value = om.valueToTree(body.get("value"));
        if ("ad".equals(key)) {
            List<String> bad = AdConfigValidator.problems(value);
            if (!bad.isEmpty()) throw new BizException(String.join("；", bad));
        }
        List<String> badCompliance = ComplianceConfigValidator.problems(key, value);
        if (!badCompliance.isEmpty()) throw new BizException(String.join("；", badCompliance));
        List<String> badEngine = EngineConfigValidator.problems(key, value);
        if (!badEngine.isEmpty()) throw new BizException(String.join("；", badEngine));
        AppConfigRow r = new AppConfigRow();
        r.setCfgKey(key);
        try { r.setCfgValue(om.writeValueAsString(body.get("value"))); }
        catch (Exception e) { throw new BizException("value 序列化失败"); }
        r.setCategory(String.valueOf(body.getOrDefault("category", "general")));
        r.setRemark(body.get("remark") == null ? null : String.valueOf(body.get("remark")));
        r.setUpdatedBy(by);
        write(r, Expect.parse(expect));
        support.publishContent(); // 配置随 bundle 下发，需失效缓存
        audit.log(by, "config.upsert", key, Map.of("expect", expect == null ? "" : expect), req);
        AppConfigRow now = config.get(key);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("key", key);
        // 面板据此换掉手上的版本号：不回时间戳的话，同一个对话框连点两次保存会把自己上一次改动判成冲突
        out.put("updatedAt", now == null ? null : now.getUpdatedAt());
        return ApiResponse.ok(out);
    }

    /**
     * 按 {@link Expect} 落笔。三条路径对应面板的三种状态，判断只在库里做（{@code updated_at} 由
     * 列上的 {@code ON UPDATE CURRENT_TIMESTAMP(3)} 盖），所以并发下的先后以库的顺序为准。
     */
    private void write(AppConfigRow r, Expect exp) {
        switch (exp.kind()) {
            case FORCE -> config.upsert(r);
            case NEW -> {
                try {
                    config.insertOnly(r);
                } catch (DuplicateKeyException e) {
                    AppConfigRow cur = config.get(r.getCfgKey());
                    throw BizException.createdAfter("配置项「" + r.getCfgKey() + "」",
                            cur == null ? null : cur.getUpdatedAt(), cur == null ? null : cur.getUpdatedBy());
                }
            }
            case UNCHANGED -> {
                if (config.updateIfUnchanged(r, exp.requireToken()) == 0) {
                    AppConfigRow cur = config.get(r.getCfgKey());
                    throw BizException.staleWrite("配置项「" + r.getCfgKey() + "」",
                            cur == null ? null : cur.getUpdatedAt(), cur == null ? null : cur.getUpdatedBy());
                }
            }
        }
    }

    /** 条件删除（H6-2）：这一行还是面板看到的那一版才删。 */
    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam String key, @RequestParam(required = false) String expect,
                                    HttpServletRequest req) {
        String by = support.requireWriter(req);
        Expect exp = Expect.parse(expect);
        if (exp.isNew()) throw new BizException("内部错误：删除一个已存在的键不需要「它还不存在」这个前提");
        int n = exp.isForce() ? config.delete(key) : config.deleteIfUnchanged(key, exp.requireToken());
        // 条件删除回报 0 有两种可能：被人改过、或已经被删了。staleWrite 按当前行有没有把这两者分开说。
        if (n == 0 && !exp.isForce()) {
            AppConfigRow cur = config.get(key);
            throw BizException.staleWrite("配置项「" + key + "」",
                    cur == null ? null : cur.getUpdatedAt(), cur == null ? null : cur.getUpdatedBy());
        }
        support.publishContent();
        audit.log(by, "config.delete", key, null, req);
        return ApiResponse.ok();
    }
}
