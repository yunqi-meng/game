package com.chemera.server.controller.admin;

import com.chemera.server.common.BizException;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.security.AuthContext;
import com.chemera.server.service.ContentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class AdminSupport {
    private final ContentMapper content;
    private final ContentService contentService;
    public AdminSupport(ContentMapper content, ContentService contentService) {
        this.content = content; this.contentService = contentService;
    }

    /** 只读角色(viewer)不可写。 */
    public String requireWriter(HttpServletRequest req) {
        String role = AuthContext.role(req);
        if (role == null || "viewer".equals(role)) throw BizException.forbidden("当前角色无写权限");
        return AuthContext.adminName(req);
    }

    /** 管理员账号本身、以及"重置玩家口令"这类等于顶号的操作只有超管能做。 */
    public String requireSuper(HttpServletRequest req) {
        if (!"super".equals(AuthContext.role(req))) throw BizException.forbidden("仅超级管理员可执行此操作");
        return AuthContext.adminName(req);
    }

    public void publishContent() {
        content.bumpVersion();
        contentService.invalidate();
    }
}
