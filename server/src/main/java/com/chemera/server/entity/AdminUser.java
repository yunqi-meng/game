package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AdminUser {
    private Long id;
    private String username;
    private String passHash;
    private String role;         // super/editor/viewer
    private Integer status;
    private Integer mustChangePassword;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime updatedAt;
}
