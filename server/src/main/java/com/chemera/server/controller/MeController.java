package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.entity.AppUser;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.security.AuthContext;
import com.chemera.server.service.SaveService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class MeController {
    private final UserMapper users;
    private final SaveService saves;
    public MeController(UserMapper users, SaveService saves) { this.users = users; this.saves = saves; }

    @GetMapping
    public ApiResponse<Map<String, Object>> me(HttpServletRequest req) {
        long uid = AuthContext.uid(req);
        AppUser u = users.findById(uid);
        Map<String, Object> m = new LinkedHashMap<>();
        if (u != null) {
            m.put("user", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("guest", u.getIsGuest() != null && u.getIsGuest() == 1);
        }
        m.putAll(saves.get(uid));
        return ApiResponse.ok(m);
    }
}
