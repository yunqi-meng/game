package com.chemera.server.service;

import com.chemera.server.entity.ContentItem;
import com.chemera.server.game.ContentRegistry;
import com.chemera.server.game.ContentSchema;
import com.chemera.server.mapper.ContentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容体检（【内容管理·健康检查】那份结果）与它的缓存。
 *
 * <p>以前 {@code GET /admin/api/content/health} 每来一次请求就把<b>全部启用行</b>（458 行，每行一份
 * JSON 原文）读进 JVM、逐行解析再逐行校验。面板一打开、每次点【重新体检】都这么来一遍，
 * 而这段时间里没人改过内容——结果当然一个字都不差，付的却是整表扫描的代价。
 *
 * <p>缓存的键是 {@code content_version}，不是时间：后台每一次写内容（含开关、删除、回滚）都会
 * {@code bumpVersion()}，所以"库里变了"这件事本身就等价于"该重扫了"；这一层跟着
 * {@link com.chemera.server.game.ContentRegistry} 的按版本缓存走同一个口径，不多造一套失效协议。
 * 代价是"另一个进程直接改了库、版本没走这条路径时，本机看到的是旧结果"——所以留了
 * {@code fresh=true} 这条路，面板上那个【重新体检】按钮走的就是它（按钮点了却回缓存，等于那个按钮是死的）。
 *
 * <p>响应里 {@code runId} 是这份结果的扫描序号、{@code fromCache} 说明它是不是重扫出来的：
 * 面板据此写"结果算于第 N 次扫描"，回归据此判断缓存真的生效了、也真的会随内容写入作废，
 * 而不是靠比较时间戳碰运气（同毫秒内两次扫描的 {@code computedAt} 可以完全相同）。
 */
@Service
public class ContentHealthService {

    private final ContentMapper content;
    private final ContentRegistry registry;
    private final ObjectMapper om;

    /** 当前这份结果对应的内容版本；{@code Long.MIN_VALUE} 表示还没扫过。 */
    private volatile long cachedVersion = Long.MIN_VALUE;
    private volatile Map<String, Object> cached;
    private volatile long runs;

    public ContentHealthService(ContentMapper content, ContentRegistry registry, ObjectMapper om) {
        this.content = content; this.registry = registry; this.om = om;
    }

    /**
     * 取体检结果。
     *
     * @param fresh 为真时无视缓存重扫一遍（面板的【重新体检】、回归里"改完内容立刻要看新结果"走这条）
     */
    public Map<String, Object> report(boolean fresh) {
        long v = content.version();
        Map<String, Object> hit = cached;
        if (!fresh && hit != null && v == cachedVersion) return stamp(hit, true);
        synchronized (this) {
            hit = cached;
            if (!fresh && hit != null && v == cachedVersion) return stamp(hit, true);
            Map<String, Object> result = scan(v);
            cached = result;
            cachedVersion = v;
            return stamp(result, false);
        }
    }

    /** 缓存里那份不能带 {@code fromCache}（它说的是"下一次拿出去时是不是复用的"），所以每次回一份副本。 */
    private static Map<String, Object> stamp(Map<String, Object> base, boolean fromCache) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("fromCache", fromCache);
        return out;
    }

    /** 逐行校验：字段校验 + 引用完整性为"问题"；缺年级这类提示为"告警"，不影响 ok。 */
    private Map<String, Object> scan(long version) {
        ContentSchema.Sets sets = ContentSchema.Sets.of(registry.current());
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        int checked = 0;
        for (ContentItem it : content.allEnabled()) {
            checked++;
            Map<String, Object> data;
            try {
                data = om.readValue(it.getData(), Map.class);
            } catch (Exception e) {
                issues.add(issue(it.getContentType(), it.getItemId(), List.of("数据无法解析为 JSON")));
                continue;
            }
            List<String> errs = ContentSchema.validate(it.getContentType(), data, sets);
            if (!errs.isEmpty()) { issues.add(issue(it.getContentType(), it.getItemId(), errs)); continue; }
            if ("quiz".equals(it.getContentType()) && data.get("grade") == null)
                warnings.add(issue(it.getContentType(), it.getItemId(), List.of("建议补充年级(grade)标签")));
        }
        runs++;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", checked);
        out.put("issueCount", issues.size());
        out.put("warningCount", warnings.size());
        out.put("ok", issues.isEmpty());
        out.put("version", version);
        out.put("runId", runs);
        out.put("computedAt", LocalDateTime.now());
        out.put("issues", issues);
        out.put("warnings", warnings);
        return out;
    }

    /** 主动作废（留给以后的批量回填脚本用：它绕开后台写入路径时，版本可能压根没动）。 */
    public void invalidate() {
        cachedVersion = Long.MIN_VALUE;
        cached = null;
    }

    /** 当前这份结果是被第几次扫描产出的：回归用它数"重扫了几次"，不依赖墙钟。 */
    public long scanCount() { return runs; }

    private static Map<String, Object> issue(String type, String id, List<String> errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type); m.put("id", id); m.put("errors", errors);
        return m;
    }
}
