package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserSaveRevision {
    private Long id;
    private Long userId;
    private Long revision;
    private String payload;
    private String source;
    private LocalDateTime createdAt;
}
