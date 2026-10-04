package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ContentItem {
    private String contentType;
    private String itemId;
    private String name;
    private Integer sort;
    private Integer enabled;
    private String data;         // JSON 文本（完整记录）
    private LocalDateTime updatedAt;
    private String updatedBy;
}
