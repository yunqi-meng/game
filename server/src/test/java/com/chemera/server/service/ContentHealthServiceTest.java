package com.chemera.server.service;

import com.chemera.server.entity.ContentItem;
import com.chemera.server.game.ContentRegistry;
import com.chemera.server.mapper.ContentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 内容体检的缓存语义（H6-3）。
 *
 * <p>【内容管理·健康检查】以前每次打开、每次点按钮都把全部启用行（458 行 JSON 原文）读进 JVM 重扫一遍，
 * 而这段时间里通常一个字都没改。现在结果按 {@code content_version} 缓存：库里没变就复用，
 * 后台任何一次写入都会 {@code bumpVersion()}，所以"该重扫了"这件事由版本自己说。
 *
 * <p>这一层要钉的四件事，光读代码顺序看不出来：
 * ①默认吃缓存，而且<b>真的只扫一次</b>（数 {@code allEnabled()} 的调用次数，不数时间）；
 * ②版本一变就重扫（否则运营改完内容看到的是旧结论，比慢更糟）；
 * ③{@code fresh=true} 永远重扫（面板那个【重新体检】按钮走这条路，它要是也只能吃缓存，按钮就是死的）；
 * ④发出去的每一份是副本（{@code fromCache} 说的是"这一次是不是复用的"，直接回缓存里那个 Map 的话，
 * 调用方一改就把答案写穿了，面板上"算于第 N 次扫描"从此前后矛盾）。
 *
 * <p>体检的<b>判定内容</b>（哪些算问题、哪些算提示）不在这里测：那套规则属于
 * {@code ContentSchema.validate}，已有 {@code ContentSchemaTest} 与 e2e 的 {@code data.ok} 覆盖。
 * 这里只需要一批"确定能通过 / 确定不能通过"的行，所以取的是真实的 {@code content-bundle.json} 夹具，
 * 快照走 {@link ContentRegistry} 线上那条 build 路径——体检用的存在域必须和引擎用的是同一份。
 */
class ContentHealthServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 版本、行、扫描次数都要能人为推动，所以把 mock 的三态收在一个小容器里。 */
    private static final class Table {
        final AtomicLong version = new AtomicLong(7);
        final List<ContentItem> rows = new ArrayList<>();
        int scans;
    }

    private Table t;
    private ContentHealthService svc;

    @BeforeEach
    void setUp() throws Exception {
        t = new Table();
        Map<String, Object> bundle;
        try (InputStream in = ContentHealthServiceTest.class.getResourceAsStream("/content-bundle.json")) {
            assertNotNull(in, "content-bundle.json 缺失：体检测试没有可校验的真内容");
            bundle = OM.readValue(in, new TypeReference<Map<String, Object>>() {});
        }

        ContentService cs = mock(ContentService.class);
        when(cs.bundle()).thenReturn(bundle);
        // 真 build：体检校验用的 Sets 与引擎吃的必须是同一份（ContentSchema.Sets.of 的注释要求的正是这件事）
        ContentRegistry registry = new ContentRegistry(cs, OM);

        ContentMapper content = mock(ContentMapper.class);
        when(content.version()).thenAnswer(i -> t.version.get());
        when(content.allEnabled()).thenAnswer(i -> {
            t.scans++;
            return new ArrayList<>(t.rows);
        });
        svc = new ContentHealthService(content, registry, OM);

        // 两行真内容：元素行字段齐全；答题行没写 grade —— 后者正是"提示"那一档
        t.rows.add(row("element", json(data(bundle, "element", 0))));
        t.rows.add(row("quiz", json(data(bundle, "quiz", 0))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> bundle, String type, int index) {
        Map<String, Object> content = (Map<String, Object>) bundle.get("content");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) content.get(type);
        assertNotNull(rows, "夹具里没有 " + type + " 类型的内容行");
        assertTrue(rows.size() > index, "夹具里的 " + type + " 行不足 " + (index + 1) + " 条");
        return rows.get(index);
    }

    private static String json(Map<String, Object> m) {
        try {
            return OM.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ContentItem row(String type, String data) {
        ContentItem it = new ContentItem();
        it.setContentType(type);
        it.setItemId("t");
        it.setData(data);
        return it;
    }

    /* ---------------- ①默认吃缓存：整表扫描真的只发生一次 ---------------- */

    @Test
    void repeatReadsScanOnce() {
        Map<String, Object> a = svc.report(false);
        Map<String, Object> b = svc.report(false);
        assertEquals(1, t.scans, "版本没动就不该再扫一遍表：这正是这次要省下来的开销");
        assertEquals(Boolean.FALSE, a.get("fromCache"), "第一次是重扫出来的");
        assertEquals(Boolean.TRUE, b.get("fromCache"), "第二次吃缓存");
        assertEquals(a.get("runId"), b.get("runId"), "复用缓存时那份结果的扫描序号必须还是原来那个");
        assertEquals(a.get("checked"), b.get("checked"));
    }

    /** 发出去的必须是副本：调用方改一下拿到的 Map，不能把缓存里那份一起改了。 */
    @Test
    void handedOutReportsAreCopies() {
        Map<String, Object> got = svc.report(false);
        got.put("fromCache", null);          // 模拟有人图省事直接改返回值
        got.put("checked", 9999);
        Map<String, Object> again = svc.report(false);
        assertEquals(Boolean.TRUE, again.get("fromCache"), "缓存里那份被外头改掉了：返回的不是副本");
        assertEquals(2, again.get("checked"), "checked 被写穿了——下一次读看到的是上一次改过的值");
    }

    /* ---------------- ②版本一变就重扫 ---------------- */

    @Test
    void versionBumpInvalidates() {
        svc.report(false);
        assertEquals(1, t.scans);
        t.version.incrementAndGet();         // 后台写内容走的就是那句 bumpVersion
        Map<String, Object> after = svc.report(false);
        assertEquals(2, t.scans, "内容变了而体检还在吃旧结果：运营改完开关却看见\"没问题\"");
        assertEquals(Boolean.FALSE, after.get("fromCache"));
        assertEquals(8L, ((Number) after.get("version")).longValue());
    }

    /**
     * 缓存的键是版本号而不是时间：同版本连续两次读必须复用，改一次版本必须重扫。
     * 这里刻意不断言 {@code computedAt} 的大小——同毫秒内两次扫描的它可以完全相同。
     */
    @Test
    void versionIsTheCacheKey() {
        Map<String, Object> v7 = svc.report(false);
        t.version.incrementAndGet();
        Map<String, Object> v8 = svc.report(false);
        assertNotEquals(v7.get("runId"), v8.get("runId"), "重扫那次必须是新的第 N 次");
        assertEquals(2, t.scans);
        assertNotEquals(v7.get("version"), v8.get("version"), "结果里要带着它依据的版本，面板才说得出\"这份是哪一版的\"");
    }

    /* ---------------- ③fresh=true 是那条逃生口 ---------------- */

    @Test
    void freshAlwaysRescans() {
        svc.report(false);
        Map<String, Object> f1 = svc.report(true);
        Map<String, Object> f2 = svc.report(true);
        assertEquals(3, t.scans, "【重新体检】必须绕过缓存，哪怕版本一个字没动");
        assertEquals(Boolean.FALSE, f1.get("fromCache"));
        assertEquals(Boolean.FALSE, f2.get("fromCache"));
        assertNotEquals(f1.get("runId"), f2.get("runId"), "连续两次重扫要看得出来是两次（回归数的就是这个）");
        int before = t.scans;
        svc.report(false);
        assertEquals(before, t.scans, "fresh 那次也得把缓存填上，否则它反倒让面板每点一次就全表扫一次");
    }

    /** 留给批量回填脚本的主动作废：它绕开后台写入路径时，版本可能压根没动。 */
    @Test
    void invalidateForcesRescan() {
        svc.report(false);
        svc.invalidate();
        svc.report(false);
        assertEquals(2, t.scans);
    }

    /* ---------------- ④坏行会让 ok 变 false；提示不会 ---------------- */

    @Test
    void brokenRowMakesItNotOk() {
        t.rows.add(row("element", "{这不是 JSON"));
        Map<String, Object> r = svc.report(true);
        assertEquals(Boolean.FALSE, r.get("ok"), "有一行读不出来却报 ok，面板就会写着\"可安全下发\"");
        assertEquals(1, r.get("issueCount"));
        // 未登记的内容类型同样要被点名，而不是"校验器里没有它所以通过"
        t.rows.add(row("no_such_type", "{\"id\":\"Z\"}"));
        Map<String, Object> r2 = svc.report(true);
        assertEquals(2, r2.get("issueCount"));
        assertTrue(((List<?>) r2.get("issues")).toString().contains("未知内容类型"),
                "问题清单里要写明是哪一类错，只报个数字的话运营还得自己猜");
    }

    /**
     * 干净内容 + 缺年级标签：记提示、不改 ok。
     *
     * <p>这条是"体检别把非阻断的事报成阻断"的唯一证据：夹具里那道答题就没写 grade，
     * 若把它算进 {@code issueCount}，面板顶上的红条会说"存在会导致下发异常的问题"，
     * 而那一行其实照常能下发。
     */
    @Test
    void warningDoesNotBlock() {
        Map<String, Object> r = svc.report(true);
        assertEquals(Boolean.TRUE, r.get("ok"), "只缺年级标签不该判成下发异常");
        assertTrue(((Number) r.get("warningCount")).intValue() >= 1, "夹具里的答题行没写 grade，应当落成一条提示");
        assertEquals(0, r.get("issueCount"));
        assertEquals(2, r.get("checked"), "checked 数的是扫过的行数，不是问题数");
    }

    /** 扫描计数与"第几次扫描"是同一件事的两个出口：前者给回归数次数，后者给面板那句"算于第 N 次扫描"。 */
    @Test
    void scanCountTracksRuns() {
        assertEquals(0, svc.scanCount(), "还没读过就不该有扫描次数");
        assertEquals(1L, ((Number) svc.report(false).get("runId")).longValue());
        t.version.incrementAndGet();
        assertEquals(2L, ((Number) svc.report(false).get("runId")).longValue());
        assertEquals(2, svc.scanCount());
    }

    /** 空表不是错误：没有内容行时 ok 为真、checked 为 0，面板该显示"没有可检查的行"而不是红条。 */
    @Test
    void emptyTableIsNotAFailure() {
        t.rows.clear();
        Map<String, Object> r = svc.report(true);
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(0, r.get("checked"));
        assertEquals(0, r.get("issueCount"));
    }

    /** 哨兵：桩没接上的话，上面每一条都会变成"扫了 0 次、0 行、ok 为真"的假绿。 */
    @Test
    void theStubActuallyScans() {
        Map<String, Object> r = svc.report(false);
        assertEquals(1, t.scans, "allEnabled 一次都没被调过，那\"只扫一次\"整条判据就是空话");
        assertEquals(2, r.get("checked"), "桩里摆了两行真内容，checked 不是 2 说明行没喂进去");
    }
}
