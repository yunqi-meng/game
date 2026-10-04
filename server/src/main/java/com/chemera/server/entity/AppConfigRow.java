package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppConfigRow {
    private String cfgKey;
    private String cfgValue;     // JSON 文本
    private String category;
    private String remark;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
