package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserSession {
    private Long id;
    private Long userId;
    private String refreshHash;
    private String device;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime rotatedAt;
}
