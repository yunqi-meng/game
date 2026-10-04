package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserSave {
    private Long userId;
    private String payload;      // JSON 文本
    private Long revision;
    private LocalDateTime updatedAt;
}
