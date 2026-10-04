package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.mapper.AdminMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 管理员账号 CRUD。此前 admin_user 有表无接口，超管改密只能手写 SQL——
 * 意味着默认口令 admin123 在真实部署里几乎必然存活。这里补齐三条底线：
 * 口令强度比玩家侧更严、初始/被重置口令必须先改才能用后台、任何操作都不能把最后一个在岗超管弄没。
 */
@Service
public class AdminAccountService {
    /** 后台账号名要出现在审计日志里，故限定可打印 ASCII，不给中文/空格留歧义。 */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{2,31}$");
    private static final Set<String> ROLES = Set.of("super", "editor", "viewer");
    private static final Set<String> WEAK = Set.of(
            "admin123", "adminadmin", "12345678", "password", "pass1234", "qwertyui", "88888888", "changeme");
    static final int MIN_PASS = 8;
    private final AdminMapper admins;
    private final PasswordEncoder enc;

    public AdminAccountService(AdminMapper admins, PasswordEncoder enc) {
        this.admins = admins; this.enc = enc;
    }

    public List<Map<String, Object>> list() {
        return admins.list().stream().map(AdminAccountService::row).toList();
    }

    private static Map<String, Object> row(AdminUser a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("user", a.getUsername());
        m.put("role", a.getRole());
        m.put("status", a.getStatus() == null ? 0 : a.getStatus());
        m.put("mustChange", a.getMustChangePassword() != null && a.getMustChangePassword() == 1);
        m.put("createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
        m.put("lastLoginAt", a.getLastLoginAt() == null ? null : a.getLastLoginAt().toString());
        return m;
    }

    /** 新建账号：口令由操作者转交，因此落库即带"首次登录必须改密"位。 */
    public Map<String, Object> create(String username, String pass, String role) {
        String u = username == null ? "" : username.trim();
        if (!NAME.matcher(u).matches())
            throw new BizException("管理员账号需 3-32 位，字母开头，仅含字母/数字/._-");
        checkPass(u, pass);
        if (!ROLES.contains(role)) throw new BizException("角色只能是 super / editor / viewer");
        if (admins.findByUsername(u) != null) throw new BizException("该管理员账号已存在");
        AdminUser a = new AdminUser();
        a.setUsername(u);
        a.setPassHash(enc.encode(pass));
        a.setRole(role);
        a.setMustChangePassword(1);
        admins.insert(a);
        return row(a);
    }

    /** 超管重置他人口令：同样打"必须改密"位，避免长期口令被人知道后一直能用。 */
    public void resetPassword(long id, String pass) {
        AdminUser a = must(id);
        checkPass(a.getUsername(), pass);
        admins.updatePass(id, enc.encode(pass), 1);
    }

    /** 自助改密：验原口令，且换掉后不再要求改密。 */
    public void changeOwnPassword(long id, String oldPass, String newPass) {
        AdminUser a = must(id);
        if (!enc.matches(oldPass == null ? "" : oldPass, a.getPassHash()))
            throw new BizException("原密码不正确");
        checkPass(a.getUsername(), newPass);
        if (oldPass.equals(newPass)) throw new BizException("新密码不能与原密码相同");
        admins.updatePass(id, enc.encode(newPass), 0);
    }

    public void setRole(long id, String role, long operatorId) {
        AdminUser a = must(id);
        if (!ROLES.contains(role)) throw new BizException("角色只能是 super / editor / viewer");
        if (role.equals(a.getRole())) return;
        if ("super".equals(a.getRole()) && !"super".equals(role)) {
            if (id == operatorId) throw new BizException("不能降级自己的超管角色");
            guardLastSuper(a);
        }
        admins.updateRole(id, role);
    }

    /** status：0=在岗，1=停用（沿用登录侧既有约定）。 */
    public void setStatus(long id, int status, long operatorId) {
        AdminUser a = must(id);
        if (status != 0 && status != 1) throw new BizException("状态只能是 0（启用）或 1（停用）");
        if (a.getStatus() != null && a.getStatus() == status) return;
        if (status == 1) {
            if (id == operatorId) throw new BizException("不能停用自己：会导致当前会话立刻失去权限");
            guardLastSuper(a);
        }
        admins.updateStatus(id, status);
    }

    public void delete(long id, long operatorId) {
        AdminUser a = must(id);
        if (id == operatorId) throw new BizException("不能删除自己");
        guardLastSuper(a);
        admins.delete(id);
    }

    /** 一个后台只要有账号可登录就不怕丢；一个都没有则是永久性自锁，故四类操作共用这一道闸。 */
    private void guardLastSuper(AdminUser target) {
        if (!"super".equals(target.getRole())) return;
        boolean active = target.getStatus() == null || target.getStatus() == 0;
        if (active && admins.countActiveSuper() <= 1)
            throw new BizException("必须至少保留一个在岗的超级管理员");
    }

    private AdminUser must(long id) {
        AdminUser a = admins.findById(id);
        if (a == null) throw new BizException("管理员账号不存在");
        return a;
    }

    private void checkPass(String username, String pass) {
        if (pass == null || pass.length() < MIN_PASS) throw new BizException("管理员密码至少 " + MIN_PASS + " 位");
        String low = pass.toLowerCase(Locale.ROOT);
        if (WEAK.contains(low) || low.equals(username.toLowerCase(Locale.ROOT)))
            throw new BizException("密码过于常见，请换一组");
        if (low.contains("admin123")) throw new BizException("密码不能包含默认口令");
    }
}
