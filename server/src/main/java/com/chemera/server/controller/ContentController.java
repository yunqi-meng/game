package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.service.ContentService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** 内容与配置一次性下发（客户端启动拉取并按 version 缓存，离线回退内嵌数据）。 */
@RestController
@RequestMapping("/api/content")
public class ContentController {
    private final ContentService content;
    public ContentController(ContentService content) { this.content = content; }

    @GetMapping("/bundle")
    public ResponseEntity<Map<String, Object>> bundle() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(content.bundle());
    }

    @GetMapping("/version")
    public ApiResponse<Map<String, Object>> version() {
        Object v = content.bundle().get("version");
        return ApiResponse.ok(Map.of("version", v));
    }
}
