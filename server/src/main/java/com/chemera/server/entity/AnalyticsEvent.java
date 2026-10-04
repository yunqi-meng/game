package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AnalyticsEvent {
    private Long id;
    private Long userId;
    private String event;
    private String props;        // JSON 文本
    private LocalDate day;
    private LocalDateTime createdAt;
}
