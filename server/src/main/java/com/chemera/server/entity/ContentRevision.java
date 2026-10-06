package com.chemera.server.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内容被覆盖前的那一版（G3）。字段含义与列注释都在 {@code V11__content_revision.sql}。
 *
 * <p>{@code dataJson} 对应列 {@code data_json}（{@code map-underscore-to-camel-case} 映射），
 * 刻意不叫 {@code data}：{@link ContentItem#getData()} 那个字段才是"当前生效的"，
 * 两个同名不同源的字段放在相邻两层，读代码的人会串。
 */
@Data
public class ContentRevision {
    private Long id;
    private String contentType;
    private String itemId;
    private String name;
    private Integer sort;
    private Integer enabled;
    private String dataJson;
    private Long version;
    private String operator;
    private String source;
    private LocalDateTime createdAt;
}
