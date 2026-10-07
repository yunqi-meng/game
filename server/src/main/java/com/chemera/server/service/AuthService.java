package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.AppUser;
import com.chemera.server.entity.UserSession;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.mapper.ModerationMapper;
import com.chemera.server.mapper.SaveMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.security.JwtService;
import com.chemera.server.security.TapTapVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USER = Pattern.compile("^[\\w\\u4e00-\\u9fa5]{2,24}$");
    /** 并发刷新的容忍窗口：这一刻内被轮换掉的令牌再出现不视为盗用（多标签页会同时刷）。 */
    private static final long ROTATE_GRACE_SEC = 15;
    private final UserMapper users;
    private final SessionMapper sessions;
    private final ModerationMapper mod;
    private final SaveMapper saves;
    private final AnalyticsMapper analytics;
    private final PasswordEncoder enc;
    private final JwtService jwt;
    private final long refreshTtlMs;
    /** 昵称写入走的那道分级判定（G2）：level≥2 才拒，level==1 只提示。 */
    private final com.chemera.server.game.SensitiveFilter wordFilter;
    /** 删号清单（G6）：与后台删号共用同一份，两个入口不再有两种语义。 */
    private final AccountPurge purge;

    /**
     * Spring 装配入口：过滤器必须是**共享的那一个**，否则后台加一个词只清了它自己那份缓存，
     * 注册这条路还在用旧表。删号清单同理必须是共享的那一份（{@link AccountPurge}），
     * 否则"玩家自助注销"和"后台删号"又会各清各的。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(UserMapper users, SessionMapper sessions, ModerationMapper mod, SaveMapper saves,
                       AnalyticsMapper analytics, PasswordEncoder enc, JwtService jwt,
                       @org.springframework.beans.factory.annotation.Value("${chemera.jwt.refresh-ttl-days:30}") long days,
                       com.chemera.server.game.SensitiveFilter words, AccountPurge purge) {
        this.users = users; this.sessions = sessions; this.mod = mod;
        this.saves = saves; this.analytics = analytics;
        this.enc = enc; this.jwt = jwt; this.refreshTtlMs = days * 86400_000L;
        this.wordFilter = words; this.purge = purge;
    }

    /** 测试用：不注入过滤器与删号清单时，用同一批 mock 现搭（口径与装配版一致，只是没有缓存/代理）。 */
    public AuthService(UserMapper users, SessionMapper sessions, ModerationMapper mod, SaveMapper saves,
                       AnalyticsMapper analytics, PasswordEncoder enc, JwtService jwt, long days) {
        this(users, sessions, mod, saves, analytics, enc, jwt, days,
                new com.chemera.server.game.SensitiveFilter(mod, 0),
                new AccountPurge(users, sessions, saves, analytics,
                        noOp(com.chemera.server.mapper.AdTicketMapper.class), mod));
    }

    /**
     * 测试里没给 {@code AdTicketMapper} 时的空实现：所有方法回 0。
     * 只服务于"这条用例根本不关心广告工单"的场景，生产装配走上面那个构造器，永远拿不到它。
     *
     * <p>为什么写成一串 if 而不是三目：{@code cond ? 0 : cond2 ? 0L : null} 这种嵌套三目，
     * 在 Java 里会因为"两个分支都能当数值"而<b>整体提升到 long</b>——于是给 {@code int} 返回的方法
     * 递回去的是一个 {@code Long}，代理拆箱时直接 {@code ClassCastException}。
     * 这条不是假想：{@code AccountPurge} 一加进来，注销那两条老用例就是这么炸的。
     */
    @SuppressWarnings("unchecked")
    private static <T> T noOp(Class<T> iface) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (p, m, a) -> {
                    Class<?> r = m.getReturnType();
                    if (r == int.class || r == Integer.class) return 0;
                    if (r == long.class || r == Long.class) return 0L;
                    if (r == boolean.class || r == Boolean.class) return Boolean.FALSE;
                    return null;
                });
    }

    /**
     * 发一对令牌，并把这一次登录落成一行会话（A6）。
     *
     * <p>顺序是有意的：先插会话行拿到自增 id，再把它签进访问令牌。反过来做就得让令牌承诺一个
     * 还没存在的 sid，而那正是"令牌与登录态脱钩"的起点。
     */
    private Map<String, Object> token(AppUser u) {
        String refresh = newToken();
        UserSession s = new UserSession();
        s.setUserId(u.getId());
        s.setRefreshHash(sha(refresh));
        s.setDevice("web");
        s.setExpiresAt(LocalDateTime.now().plusSeconds(refreshTtlMs / 1000));
        sessions.insert(s);
        String access = jwt.issue(u.getId(), "user", "user", u.getUsername(), s.getId());
        boolean guest = u.getIsGuest() != null && u.getIsGuest() == 1;
        return Map.of("token", access, "refresh", refresh, "user", u.getUsername(),
                "guest", guest, "expiresIn", jwt.accessTtlMs() / 1000);
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    /** 游客试玩：服务端建一次性账号（随机不可见口令）并发常规令牌；转正时由 {@link #upgrade} 并入正式账号。 */
    public Map<String, Object> guest() {
        AppUser u = new AppUser();
        String name = "游客" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        u.setUsername(name);
        u.setNickname(name);
        u.setPassHash(enc.encode(UUID.randomUUID().toString()));
        u.setIsGuest(1);
        try {
            users.insertGuest(u);
        } catch (DuplicateKeyException e) {
            throw new BizException("游客创建失败，请重试");
        }
        return token(u);
    }

    public Map<String, Object> register(String username, String pass, String nickname) {
        return token(createUser(username, pass, nickname));
    }

    private AppUser createUser(String username, String pass, String nickname) {
        if (username == null || !USER.matcher(username).matches()) throw new BizException("用户名需 2-24 位（中文/字母/数字/下划线）");
        if (pass == null || pass.length() < 6) throw new BizException("密码至少 6 位");
        checkSensitive(username);
        if (nickname != null) checkSensitive(nickname);
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPassHash(enc.encode(pass));
        u.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        try {
            users.insert(u);
        } catch (DuplicateKeyException e) {
            throw new BizException("用户名已被占用");
        }
        return u;
    }

    /**
     * 游客转正：用当前游客令牌注册正式账号，并把游客名下的存档/历史/埋点整体并入新账号。
     * 游客行随后删除，旧刷新令牌失效。
     */
    @Transactional
    public Map<String, Object> upgrade(long guestUid, String username, String pass, String nickname) {
        AppUser g = guestOrThrow(guestUid);
        AppUser u = createUser(username, pass, nickname);
        mergeFrom(g.getId(), u.getId());
        Map<String, Object> out = new LinkedHashMap<>(token(u));
        out.put("guest", false);
        out.put("merged", true);
        return out;
    }

    /**
     * TapTap 登录：票据已经由 {@code TapTapVerifier} 验签成 openId，这里只负责"变成本服账号"。
     *
     * <p>三种情况分开处理，因为它们对玩家数据的后果完全不同：
     * <ol>
     *   <li>openId 已绑过 → 就是登录，直接发一对令牌；</li>
     *   <li>openId 没绑过、且带了游客令牌 → 建档并把游客进度整体并入（同【转正】那条路径），
     *       这是"试玩觉得不错、想用 TapTap 接着玩"的正路；</li>
     *   <li>openId 没绑过、没带游客令牌 → 建档即新号。</li>
     * </ol>
     *
     * <p>刻意**不**做的一件事：openId 已经绑了账号、同时又递来游客档时，把两档硬 merge 到一起。
     * 那种合并必然覆盖其中一份存档，而覆盖是不可逆的——所以这里直接回绝，让玩家自己选留哪份。
     */
    @Transactional
    public Map<String, Object> taptap(TapTapVerifier.Identity id, Long guestUid) {
        if (id == null || id.openId() == null || id.openId().isBlank())
            throw BizException.unauthorized("TapTap 身份不完整");
        AppUser bound = users.findByTapTap(id.openId());
        if (bound != null) {
            if (guestUid != null)
                throw new BizException("这个 TapTap 账号已经有存档了，不会再并入游客档；"
                        + "请先用该 TapTap 账号登录，游客进度可在【设置·导出存档】里留存");
            return issue(bound, false);
        }
        // 先把游客档验干净再建档：拿一段坏令牌来不该在库里留下一行没有主人的正式账号
        AppUser guest = guestUid == null ? null : guestOrThrow(guestUid);
        AppUser created = createExternal(id);
        if (guest != null) {
            mergeFrom(guest.getId(), created.getId());
            return issue(created, true);
        }
        return issue(created, false);
    }

    /** 登录成功的统一出口：封禁检查 → 记一次登录 → 发一对令牌。merged 只影响回给客户端的标记。 */
    private Map<String, Object> issue(AppUser u, boolean merged) {
        if (u.getStatus() != null && u.getStatus() == 1
                && (u.getBannedUntil() == null || u.getBannedUntil().isAfter(LocalDateTime.now())))
            throw BizException.forbidden("账号已被封禁");
        users.touchLogin(u.getId());
        Map<String, Object> out = new LinkedHashMap<>(token(u));
        out.put("guest", false);
        out.put("bound", true);
        if (merged) out.put("merged", true);
        return out;
    }

    /**
     * 第三方登录建档：用户名由 openId 派生（玩家看不到也不用它登录），口令位放一个随机串占位——
     * 这个账号没有口令，要设口令得走【转正】或后台重置。
     */
    private AppUser createExternal(TapTapVerifier.Identity id) {
        AppUser u = new AppUser();
        u.setUsername("tap" + sha(id.openId()).substring(0, 16));
        String nick = TapTapVerifier.sanitizeName(id.name());
        if (nick.isBlank()) nick = "TapTap玩家";
        checkSensitive(nick);
        u.setNickname(nick);
        u.setPassHash(enc.encode(UUID.randomUUID().toString()));
        u.setTaptapOpenId(id.openId());
        try {
            users.insertExternal(u);
        } catch (DuplicateKeyException e) {
            // uk_taptap_open 撞车=同一个人在两个请求里同时首次登录，回一句重试比给两份存档好
            throw new BizException("TapTap 登录正在建档，请重试");
        }
        return u;
    }

    private AppUser guestOrThrow(long guestUid) {
        AppUser g = users.findById(guestUid);
        if (g == null || g.getIsGuest() == null || g.getIsGuest() != 1)
            throw new BizException("当前登录的不是游客账号，无需转正");
        return g;
    }

    /** 并档的唯一实现：存档、存档历史、埋点整体迁到新账号，游客的登录态全撤、游客行删除。 */
    private void mergeFrom(long fromUid, long toUid) {
        saves.reassignUser(fromUid, toUid);
        saves.reassignRevisions(fromUid, toUid);
        analytics.reassignUser(fromUid, toUid);
        sessions.revokeAll(fromUid, LocalDateTime.now());
        users.delete(fromUid);
    }

    public Map<String, Object> login(String username, String pass) {
        AppUser u = users.findByUsername(username == null ? "" : username);
        if (u == null || !enc.matches(pass == null ? "" : pass, u.getPassHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (u.getStatus() != null && u.getStatus() == 1) {
            if (u.getBannedUntil() == null || u.getBannedUntil().isAfter(LocalDateTime.now()))
                throw BizException.forbidden("账号已被封禁");
        }
        users.touchLogin(u.getId());
        return token(u);
    }

    /**
     * 刷新即轮换：本次使用的刷新令牌当场作废（rotated_at），只下发新的一对。
     * 三类令牌要分开对待——硬撤销（退出/改密/注销）绝不补发；被轮换掉的令牌在宽限期内
     * 视为多标签页并发刷新，超期再出现即按泄露处理，整户下线。
     *
     * <p>整段只用<b>一个</b> {@code now}：写 {@code rotated_at} 用的是它，判"是否超出宽限期"用的也是它，
     * 连坐撤销时盖的仍是它。这里绝不能再引入第二个时钟（比如让 SQL 的 {@code NOW()} 去盖戳、
     * 由 Java 来判），否则库与进程时区不一致时，"刚轮换"会被算成"早就过期"——
     * 迭代 4 的 CI 首跑正是这样把玩家整户下线的（详见 {@link SessionMapper} 的注释）。
     */
    public Map<String, Object> refresh(String refresh) {
        if (refresh == null) throw BizException.unauthorized("缺少刷新令牌");
        String h = sha(refresh);
        UserSession s = sessions.findByHash(h);
        if (s == null) throw BizException.unauthorized("刷新令牌无效");
        LocalDateTime now = LocalDateTime.now();
        if (s.getRevokedAt() != null) throw BizException.unauthorized("该登录态已注销，请重新登录");
        if (s.getRotatedAt() != null) {
            if (s.getRotatedAt().isBefore(now.minusSeconds(ROTATE_GRACE_SEC))) {
                sessions.revokeAll(s.getUserId(), now);
                log.warn("刷新令牌重用（超出轮换宽限期），已吊销 uid={} 的全部登录态", s.getUserId());
                throw BizException.unauthorized("登录状态异常，请重新登录");
            }
        } else if (s.getExpiresAt() == null || s.getExpiresAt().isBefore(now)) {
            throw BizException.unauthorized("刷新令牌已过期");
        } else {
            sessions.rotate(h, now);
        }
        AppUser u = users.findById(s.getUserId());
        if (u == null) throw BizException.unauthorized("账号不存在");
        return token(u);
    }

    public void logout(String refresh) {
        if (refresh != null) sessions.revoke(sha(refresh), LocalDateTime.now());
    }

    public void changePassword(long uid, String oldPass, String newPass) {
        AppUser u = users.findById(uid);
        if (u == null || !enc.matches(oldPass == null ? "" : oldPass, u.getPassHash()))
            throw new BizException("原密码不正确");
        if (newPass == null || newPass.length() < 6) throw new BizException("新密码至少 6 位");
        users.updatePass(uid, enc.encode(newPass));
        sessions.revokeAll(uid, LocalDateTime.now()); // 改密后其余设备登录态失效
    }

    /**
     * 忘记密码的唯一出路（没有邮件服务可验证身份）：由超管在后台核实身份后重置。
     * 刻意排除游客档——游客从未设置过口令，重置等于凭空造一个可登录账号，
     * 且游客名是随机后缀，客服无从核对。
     */
    public void adminResetPassword(long uid, String newPass) {
        AppUser u = users.findById(uid);
        if (u == null) throw new BizException("账号不存在");
        if (u.getIsGuest() != null && u.getIsGuest() == 1)
            throw new BizException("游客档没有口令，无法重置；请让玩家在游戏内【设置-账号】转正");
        if (newPass == null || newPass.length() < 6) throw new BizException("新密码至少 6 位");
        if (newPass.equalsIgnoreCase(u.getUsername())) throw new BizException("新密码不能与用户名相同");
        users.updatePass(uid, enc.encode(newPass));
        // 整户刷新令牌作废：被盗设备最多还能用完手上的访问令牌（≤2h），续不了命
        sessions.revokeAll(uid, LocalDateTime.now());
        log.info("管理员重置玩家口令 uid={} user={}", uid, u.getUsername());
    }

    /**
     * 注销账号：用户名下登录态、存档、存档历史、埋点、广告工单与举报一并物理删除
     * （表间无外键级联，必须逐个清——清单只有一份，见 {@link AccountPurge}）。
     *
     * <p>二次确认按账号种类分两条路，因为"再输一次口令"对第三方登录建出来的账号根本不成立——
     * 那一行存的是随机占位串，玩家从来没设过口令（也不该给他设：注册入口是 TapTap）。
     * 而"账号可注销"是上架合规的硬要求，所以这里改为要求重新走一次 TapTap 授权：
     * 验签回来的 openId 必须与账号上绑的那个一致，等价于"本人在场"。
     */
    @Transactional
    public void deleteAccount(long uid, String pass, TapTapVerifier.Identity reauth) {
        AppUser u = users.findById(uid);
        if (u == null) throw new BizException("账号不存在");
        String bound = u.getTaptapOpenId();
        boolean tapAccount = bound != null && !bound.isBlank();
        if (tapAccount && reauth != null && bound.equals(reauth.openId())) {
            // 本人刚用 TapTap 重新授权过：不再要求口令
            log.info("TapTap 账号凭重新授权注销 uid={} openId 已核对", uid);
        } else {
            if (!enc.matches(pass == null ? "" : pass, u.getPassHash())) {
                throw new BizException(tapAccount
                        ? "该账号用 TapTap 登录，注销需重新完成一次 TapTap 授权以确认身份"
                        : "密码不正确");
            }
        }
        purge.purge(uid);
    }

    /** 昵称那一路的敏感词判定（G2）：只有"拦截档"（level≥2）才把写入拒掉。 */
    private void checkSensitive(String text) {
        wordFilter.block(text);
    }

    private static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
