package com.chemera.server.service;

import com.chemera.server.mapper.ConfigMapper;
import com.chemera.server.mapper.ContentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内容下发：把 DB 中的 content_item + app_config 组装成客户端一次性拉取的 bundle，按版本号缓存。 */
@Service
public class ContentService {
    private final ContentMapper content;
    private final ConfigMapper config;
    private final ObjectMapper om;
    private volatile long cachedVersion = -1;
    private volatile Map<String, Object> cachedBundle;

    public ContentService(ContentMapper c, ConfigMapper cfg, ObjectMapper om) {
        this.content = c; this.config = cfg; this.om = om;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> bundle() {
        long v = content.version();
        Map<String, Object> local = cachedBundle;
        if (v == cachedVersion && local != null) return local;
        synchronized (this) {
            if (v == cachedVersion && cachedBundle != null) return cachedBundle;
            Map<String, List<Object>> grouped = new LinkedHashMap<>();
            for (var it : content.allEnabled()) {
                try {
                    Map<String, Object> node = om.readValue(it.getData(), Map.class);
                    String type = it.getContentType();
                    grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(node);
                } catch (Exception ignored) {}
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            for (var row : config.allRaw()) {
                try {
                    cfg.put((String) row.get("cfg_key"), om.readValue(String.valueOf(row.get("cfg_value")), Object.class));
                } catch (Exception ignored) {}
            }
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("version", v);
            bundle.put("content", grouped);
            bundle.put("config", cfg);
            cachedBundle = bundle; cachedVersion = v;
            return bundle;
        }
    }

    public void invalidate() { cachedVersion = -1; }
}
