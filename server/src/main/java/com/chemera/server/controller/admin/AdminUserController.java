package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.common.Page;
import com.chemera.server.entity.AppUser;
import com.chemera.server.game.CurfewGuard;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.service.AccountPurge;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.AuthService;
import com.chemera.server.service.PlayerAssetService;
import com.chemera.server.service.SaveService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/users")
public class AdminUserController {
    private final UserMapper users;
    private final SaveService saveService;
    private final PlayerAssetService assets;
    private final SessionMapper sessions;
    private final AuthService auth;
    private final AccountPurge purge;
    private final AdminSupport support;
    private final AuditService audit;
    private final CurfewGuard curfew;

    public AdminUserController(UserMapper users, SaveService saveService,
                               PlayerAssetService assets,
                               SessionMapper sessions, AuthService auth, AccountPurge purge,
                               AdminSupport support, AuditService audit, CurfewGuard curfew) {
        this.users = users; this.saveService = saveService;
        this.assets = assets;
        this.sessions = sessions; this.auth = auth; this.purge = purge;
        this.support = support; this.audit = audit;
        this.curfew = curfew;
    }

    @GetMapping
    public ApiResponse<Page<Map<String, Object>>> list(@RequestParam(defaultValue = "") String q,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) Integer off) {
        // 这一处本来就是 {rows,total}（H6-3 把其余几个列表接到同一条规矩上），
        // 归一参数只是让"页长上限/负偏移"这四个端点说同一句话。
        List<Map<String, Object>> rows = users.page(q, Page.size(size, 30), Page.off(off));
        return ApiResponse.ok(new Page<>(rows, users.count(q)));
    }

    @PostMapping("/ban")
    public ApiResponse<Void> ban(@RequestParam long id, @RequestParam(defaultValue = "7") int days,
                                 HttpServletRequest req) {
        String by = support.requireWriter(req);
        users.setBan(id, 1, LocalDateTime.now().plusDays(days));
        sessions.revokeAll(id);
        audit.log(by, "user.ban", "uid:" + id, Map.of("days", days), req);
        return ApiResponse.ok();
    }

    @PostMapping("/unban")
    public ApiResponse<Void> unban(@RequestParam long id, HttpServletRequest req) {
        String by = support.requireWriter(req);
        users.setBan(id, 0, null);
        audit.log(by, "user.unban", "uid:" + id, null, req);
        return ApiResponse.ok();
    }

    /**
     * 忘记密码的落地路径（C5）：没有邮件服务可验证身份，只能由超管人工核实用户名后重置。
     * 比 requireWriter 高一档——重置口令等于顶号登录，编辑角色不该有这个能力。
     */
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@RequestParam long id, @RequestBody Map<String, String> b,
                                           HttpServletRequest req) {
        String by = support.requireSuper(req);
        auth.adminResetPassword(id, b.get("pass"));
        audit.log(by, "user.resetPassword", "uid:" + id, null, req);
        return ApiResponse.ok();
    }

    /**
     * 调整单个玩家的金币/钻石。与重置口令同档（{@code requireSuper}）：它能凭空造钱，
     * 编辑角色不该有这条路径。增减值而非绝对值，服务端记前后值进审计与存档历史。
     */
    @PostMapping("/assets")
    public ApiResponse<Map<String, Object>> assets(@RequestParam long id, @RequestBody Map<String, Object> b,
                                                   HttpServletRequest req) {
        String by = support.requireSuper(req);
        Map<String, Object> r = assets.adjust(id, delta(b, "coins"), delta(b, "diamonds"));
        audit.log(by, "user.assets", "uid:" + id, r, req);
        return ApiResponse.ok(r);
    }

    private static long delta(Map<String, Object> b, String key) {
        Object v = b == null ? null : b.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        throw new BizException(key + " 必须是整数");
    }

    /**
     * 标记/取消玩家的青少年模式（合规时段闸门的唯一运营入口）。
     *
     * <p>与封禁同档（{@code requireWriter}）而不是超管档：它不碰资产也不顶号，只是给账号挂上
     * "只在放行时段可玩"的限制，客服/运营处理家长申诉时要能当场点上。
     * 反过来说它必须写审计——这个标记直接决定一个孩子能不能在游戏里待着，出了争议要能查是谁改的。
     *
     * <p>返回权威状态视图（含下次可玩时刻），运营点完立刻能核对生效没有，而不是"200 了大概成了吧"。
     */
    @PostMapping("/minor")
    public ApiResponse<Map<String, Object>> minor(@RequestParam long id,
                                                  @RequestParam(defaultValue = "false") boolean on,
                                                  HttpServletRequest req) {
        String by = support.requireWriter(req);
        AppUser u = users.findById(id);
        if (u == null) throw BizException.notFound("用户");
        users.setMinor(id, on ? 1 : 0);
        curfew.invalidate(id);
        audit.log(by, "user.minor", "uid:" + id, Map.of("on", on), req);
        return ApiResponse.ok(curfew.view(id));
    }

    @GetMapping("/save")
    public ApiResponse<Map<String, Object>> save(@RequestParam long id) {
        AppUser u = users.findById(id);
        if (u == null) throw BizException.notFound("用户");
        return ApiResponse.ok(saveService.get(id));
    }

    /**
     * 后台【存档历史】列表（G5）：一行一版，只回元信息与字节数。
     * 完整 payload 留给 {@code /save} 与 rollback 那两条按需取的路——这张表上最多 50 版，
     * 每版几十 KB，全搬进内存只为显示"3.2 KB"是本项目前最贵的一次无用功。
     */
    @GetMapping("/save/revisions")
    public ApiResponse<List<Map<String, Object>>> revisions(@RequestParam long id) {
        if (users.findById(id) == null) throw BizException.notFound("用户");
        return ApiResponse.ok(saveService.revisionMeta(id));
    }

    @PostMapping("/save/rollback")
    public ApiResponse<Void> rollback(@RequestParam long id, @RequestParam long revision, HttpServletRequest req) {
        String by = support.requireWriter(req);
        saveService.rollback(id, revision, by);
        audit.log(by, "user.save.rollback", "uid:" + id, Map.of("revision", revision), req);
        return ApiResponse.ok();
    }

    /**
     * 后台删号（G6）：与玩家自助注销走同一份清理清单（{@link AccountPurge}）。
     *
     * <p>以前这条路只删了 {@code app_user} 一行，存档、历史、埋点、广告工单全留在库里——
     * 既不符合"注销即删除全部数据"，也会让同名重建撞上残留档。现在删完把"删了几行"落进审计：
     * 事后有人问"你到底清没清"，审计里那份数字就是凭据。
     */
    @DeleteMapping
    public ApiResponse<Map<String, Object>> delete(@RequestParam long id, HttpServletRequest req) {
        String by = support.requireWriter(req);
        AppUser u = users.findById(id);
        if (u == null) throw BizException.notFound("用户");
        Map<String, Object> done = purge.purge(id);
        curfew.invalidate(id); // 防沉迷缓存里别再留着这个人的行
        audit.log(by, "user.delete", "uid:" + id, done, req);
        return ApiResponse.ok(done);
    }
}
