package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.Page;
import com.chemera.server.service.AuditService;
import com.chemera.server.mapper.ModerationMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/moderation")
public class AdminModerationController {
    private final ModerationMapper mod;
    private final AdminSupport support;
    private final AuditService audit;
    /** 词表一改就得让它作废（G2），否则新增的拦截词要等缓存 TTL 到点才生效。 */
    private final com.chemera.server.game.SensitiveFilter words;

    public AdminModerationController(ModerationMapper mod, AdminSupport support, AuditService audit,
                                     com.chemera.server.game.SensitiveFilter words) {
        this.mod = mod; this.support = support; this.audit = audit; this.words = words;
    }

    /**
     * 举报列表（H6-3）：回 {@link Page}，总数来自 {@code COUNT(*)}。
     *
     * <p>以前这里回的是<b>裸 List</b>，面板拿不到总数，就用本页长度编了一个
     * （{@code d.length < size ? 偏移 + d.length : 页码 * size + 1}）。那个数字在最后一页会说"共有 N+1 条"，
     * 在满页时永远说"还有下一页"——运营对着一个假页数翻页，翻到空页以为数据没了。
     * {@code total} 与本页行数从此是两件事，各自由库回答。
     */
    @GetMapping("/reports")
    public ApiResponse<Page<Map<String, Object>>> reports(@RequestParam(defaultValue = "") String status,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) Integer off) {
        int n = Page.size(size, 50);
        int o = Page.off(off);
        return ApiResponse.ok(new Page<>(mod.pageReports(status, n, o), mod.countReports(status)));
    }

    /**
     * 待处理条数（G2）：后台侧边栏那个"待处理 N"角标读的是它。
     *
     * <p>原来这个数是前端写死的 0，因为根本没有玩家侧入口能往举报表里写行。角标停在 0 比没有角标更糟——
     * 它会让人以为"确实没人举报"。现在这个数字来自 {@code status='open'} 的真实计数，
     * 处理完一条就少一条。
     */
    @GetMapping("/pending")
    public ApiResponse<Map<String, Object>> pending() {
        return ApiResponse.ok(Map.of("open", mod.openCount()));
    }

    @PostMapping("/reports/handle")
    public ApiResponse<Void> handle(@RequestParam long id, @RequestParam String status, HttpServletRequest req) {
        String by = support.requireWriter(req);
        if (!List.of("open", "handled", "dismissed").contains(status)) return ApiResponse.err(400, "非法状态");
        mod.handleReport(id, status, by);
        audit.log(by, "report.handle", "report:" + id, Map.of("status", status), req);
        return ApiResponse.ok();
    }

    /**
     * 词表列表（H6-3）：整张表以前一次搬给面板，词是运营一行一行加出来的，只会长不会短。
     * 现在按 {@code id DESC} 分页——加完词立刻能在第一页看到自己那条，而不是翻到最后一页去确认。
     *
     * <p>判定用的不是这个端点：{@link com.chemera.server.game.SensitiveFilter} 读的是一份全量缓存，
     * 分页在那边等于漏词，所以两条路各走各的（见 {@code ModerationMapper.words}）。
     */
    @GetMapping("/words")
    public ApiResponse<Page<Map<String, Object>>> words(@RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) Integer off) {
        int n = Page.size(size, 50);
        return ApiResponse.ok(new Page<>(mod.pageWords(n, Page.off(off)), mod.countWords()));
    }

    /**
     * 词表写入（G2）：现在只有"拦截档"（level≥2）真的会让注册/改昵称被拒，
     * 所以 level 必须校验收紧——一条 {@code level=0} 的静默失效词条，比没有词条更坑，
     * 运营会以为这个违规词已经管住了。
     */
    @PostMapping("/words")
    public ApiResponse<Map<String, Object>> upsertWord(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        String by = support.requireWriter(req);
        String word = b.get("word") == null ? "" : String.valueOf(b.get("word")).trim();
        if (word.isEmpty()) return ApiResponse.err(400, "敏感词不能为空");
        if (word.length() > 32) return ApiResponse.err(400, "敏感词最长 32 字");
        int level = b.get("level") == null ? 1 : ((Number) b.get("level")).intValue();
        if (level != 1 && level != 2) return ApiResponse.err(400, "分级只有 1（提示）与 2（拦截）两档");
        mod.upsertWord(word, level);
        words.invalidate(); // 立刻生效：不然运营加完词还得等 60 秒才测得出效果
        audit.log(by, "word.upsert", word, Map.of("level", level), req);
        return ApiResponse.ok(Map.of("word", word, "level", level));
    }

    @DeleteMapping("/words")
    public ApiResponse<Void> deleteWord(@RequestParam long id, HttpServletRequest req) {
        String by = support.requireWriter(req);
        mod.deleteWord(id);
        words.invalidate();
        audit.log(by, "word.delete", "id:" + id, null, req);
        return ApiResponse.ok();
    }

    /** 操作日志（H6-3）：同 {@link #reports} —— 这一张表只会一直长，没有总数的分页组件迟早翻页翻到空。 */
    @GetMapping("/audit")
    public ApiResponse<Page<Map<String, Object>>> audit(@RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) Integer off) {
        int n = Page.size(size, 50);
        return ApiResponse.ok(new Page<>(audit.page(n, Page.off(off)), audit.count()));
    }
}
