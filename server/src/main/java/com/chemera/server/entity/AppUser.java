package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppUser {
    private Long id;
    private String username;
    private String passHash;
    private String nickname;
    private Integer status;      // 0正常 1封禁
    private Integer isGuest;     // 0正式账号 1游客（服务端保存，注册时并入）
    private LocalDateTime bannedUntil;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
}
