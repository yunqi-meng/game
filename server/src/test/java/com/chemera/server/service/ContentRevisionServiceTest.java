package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.common.Expect;
import com.chemera.server.entity.ContentItem;
import com.chemera.server.entity.ContentRevision;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.ContentRevisionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 内容历史这一层的回归（G3）。
 *
 * <p>Mockito 而不是真库：这里要钉的是<b>顺序与取舍</b>——快照必须在写之前取（事后读到的是新行，
 * 历史会退化成"当前版本的复读"）、新建不该落一行空快照、启停这种"没碰 data"的动作同样要落、
 * 修剪只在攒够 50 版时才发 DELETE。这四条在真库上跑出来也是这四个断言，而 mock 能把它们
 * 写成一句人话；真库那条路径（JSON 列的规范化、回滚后玩家侧字节逐字相同）由 e2e 从 HTTP 侧覆盖。
 */
class ContentRevisionServiceTest {

    /**
     * 面板手上那份旧版本号（THEN）与同事刚写进去的那一版（LIVE_AT）。
     * 整份文件里只在这里各写一次，冲突消息里的时间就从这两个数推出来，改一处不会自相矛盾。
     */
    private static final LocalDateTime THEN = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime LIVE_AT = LocalDateTime.of(2026, 10, 6, 5, 20, 11, 123_000_000);

    private final ContentRevisionMapper revs = mock(ContentRevisionMapper.class);
    private final ContentMapper content = mock(ContentMapper.class);
    private final ContentRevisionService svc = new ContentRevisionService(revs, content);

    private static ContentItem row(String type, String id, String data, int enabled) {
        ContentItem it = new ContentItem();
        it.setContentType(type); it.setItemId(id); it.setName("旧名字");
        it.setSort(7); it.setEnabled(enabled); it.setData(data);
        return it;
    }

    @Test
    void snapshotGoesBeforeTheWriteAndCarriesTheOldBytes() {
        when(content.version()).thenReturn(21L);
        ContentItem before = row("element", "H", "{\"id\":\"H\",\"zh\":\"氢\"}", 1);
        ContentItem next = row("element", "H", "{\"id\":\"H\",\"zh\":\"氢-改\"}", 1);

        svc.saveOver(before, next, "yunqi");

        InOrder order = inOrder(revs, content);
        ArgumentCaptor<ContentRevision> cap = ArgumentCaptor.forClass(ContentRevision.class);
        order.verify(revs).insert(cap.capture());
        order.verify(content).upsert(next);

        ContentRevision r = cap.getValue();
        assertEquals("{\"id\":\"H\",\"zh\":\"氢\"}", r.getDataJson(), "历史存的必须是<被顶掉的那一版>，不是新行");
        assertEquals("edit", r.getSource());
        assertEquals(21L, r.getVersion(), "对账号是写完顶版之前的那个版本——玩家手里此刻就是这个号");
        assertEquals("yunqi", r.getOperator());
        assertEquals("旧名字", r.getName());
        assertEquals(7, r.getSort());
        assertEquals(1, r.getEnabled(), "enabled 也要进历史：只还原 data 的回滚会把行留在误关的状态");
        assertEquals("yunqi", next.getUpdatedBy(), "写回的人记的是这次动作的操作者");
    }

    @Test
    void creatingANewItemLeavesNoHistoryRow() {
        ContentItem next = row("reaction", "R900", "{\"id\":\"R900\"}", 1);
        svc.saveOver(null, next, "yunqi");
        verify(revs, never()).insert(any());
        verify(content).upsert(next);
    }

    @Test
    void everyEntryWritesItsOwnHistoryKind() {
        ContentItem before = row("element", "H", "{\"id\":\"H\"}", 1);

        svc.deleteOver(before, "yunqi");
        svc.toggleOver(before, 0, "yunqi");
        svc.restoreOver(before, revision(9L, "{\"id\":\"H\",\"zh\":\"旧版\"}"), "yunqi");

        ArgumentCaptor<ContentRevision> cap = ArgumentCaptor.forClass(ContentRevision.class);
        verify(revs, times(3)).insert(cap.capture());
        assertEquals(List.of("delete", "toggle", "rollback"),
                cap.getAllValues().stream().map(ContentRevision::getSource).toList(),
                "三个入口各自的来源标记：抽屉里读不出「是谁把这一行弄没的」就等于没历史");
        verify(content).delete("element", "H");
        verify(content).setEnabled("element", "H", 0, "yunqi");
        ArgumentCaptor<ContentItem> back = ArgumentCaptor.forClass(ContentItem.class);
        verify(content).upsert(back.capture());
        ContentItem rolled = back.getValue();
        assertEquals("{\"id\":\"H\",\"zh\":\"旧版\"}", rolled.getData(), "回滚写回去的就是历史那一版的字节");
        assertEquals("旧名字", rolled.getName());
        assertEquals(7, rolled.getSort());
        assertEquals(1, rolled.getEnabled());
        assertEquals("yunqi", rolled.getUpdatedBy(), "顶版的人记操作者，不是当初写下这一版的人");
    }

    @Test
    void deletingSomethingThatIsNotThereIsANoOpNotAnError() {
        svc.deleteOver(null, "yunqi");
        svc.toggleOver(null, 1, "yunqi");
        verify(revs, never()).insert(any());
        verify(content, never()).delete(anyString(), anyString());
        verify(content, never()).setEnabled(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void trimOnlyFiresOnceTheCapIsReached() {
        when(revs.floorId("element", "H", ContentRevisionService.KEEP_PER_ITEM - 1)).thenReturn(null);
        svc.saveOver(row("element", "H", "{\"id\":\"H\"}", 1), row("element", "H", "{\"id\":\"H2\"}", 1), "a");
        verify(revs, never()).deleteOlder(anyString(), anyString(), anyLong());

        reset(revs);
        when(revs.floorId("element", "H", ContentRevisionService.KEEP_PER_ITEM - 1)).thenReturn(4_200L);
        svc.saveOver(row("element", "H", "{\"id\":\"H\"}", 1), row("element", "H", "{\"id\":\"H2\"}", 1), "a");
        verify(revs).deleteOlder("element", "H", 4_200L);
    }

    @Test
    void drawerListsMetadataOnlyAndCapsTheLimit() {
        svc.history("element", "H", 5_000);
        verify(revs).listMeta("element", "H", ContentRevisionService.KEEP_PER_ITEM);
        svc.history("element", "H", 0);
        verify(revs).listMeta("element", "H", 1);
    }

    @Test
    void rollingBackToAMissingRevisionIs404() {
        when(revs.get(77L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> svc.require(77L));
        assertEquals(404, e.code, "前端要的是「这条历史不存在」，不是 500");
    }

    @Test
    void unknownSourceIsRejectedOutOfTheClosedSet() {
        BizException e = assertThrows(BizException.class,
                () -> svc.snapshot(row("element", "H", "{\"id\":\"H\"}", 1), "a", "rename"));
        assertTrue(e.getMessage().contains("rename"), "报错要带上进 closed set 的那个值，否则排查全靠猜");
        verify(revs, never()).insert(any());
    }

    /**
     * 快照与写入必须在同一个事务里。
     *
     * <p>拆开的失败长相是"历史里多了一条从没生效过的版本，而 live 行还是旧的"——照那条历史回滚，
     * 等于把一个从未存在过的状态发给玩家。控制器层没有事务，所以原子性只能钉在这一层，
     * 而 Mockito 看不出事务边界，这里直接断注解在不在。
     */
    @Test
    void writeEntryPointsAreTransactional() {
        for (String m : List.of("saveOver", "deleteOver", "toggleOver", "restoreOver")) {
            boolean found = java.util.Arrays.stream(ContentRevisionService.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(m))
                    .peek(x -> assertTrue(java.lang.reflect.Modifier.isPublic(x.getModifiers()), m + " 必须是 public 才能被代理"))
                    .anyMatch(x -> x.isAnnotationPresent(Transactional.class));
            assertTrue(found, m + " 没有 @Transactional：快照和写入会分成两次提交");
        }
    }

    /**
     * 后台改内容的三个入口都必须走 {@code ContentRevisionService}，不许再直接调 mapper。
     *
     * <p>G3 的整个前提是"每一次覆盖都留下一行"，而这条前提只要有一个入口绕过 service 就不成立——
     * 最可能漏的恰恰是"启停按钮又不碰 data，记什么历史"这种看起来不该记的。
     * 静态读源码而不是跑用例：漏的那个入口下次提交时才会出现，跑到它的用例本身就不存在。
     */
    @Test
    void controllerWritesContentOnlyThroughTheService() throws Exception {
        String src = sourceOf("com/chemera/server/controller/admin/AdminContentController.java");
        String body = stripComments(src);                              // 注释里提这些名字是解释，不是调用
        for (String call : List.of("content.upsert(", "content.delete(", "content.setEnabled(")) {
            assertFalse(body.contains(call),
                    "AdminContentController 直接调了 " + call + "：绕过 ContentRevisionService 的写入没有历史");
        }
        assertTrue(body.contains("revisions.saveOver(") && body.contains("revisions.deleteOver(")
                        && body.contains("revisions.toggleOver(") && body.contains("revisions.restoreOver("),
                "四个写入口应当都收在 ContentRevisionService 上");
    }

    /** 定位主代码源文件：mvn 的工作目录是 server/，IDE 未必，所以两条路都试，找不到就红。 */
    private static String sourceOf(String relative) throws Exception {
        Path p = Path.of("src/main/java", relative);
        if (!Files.isRegularFile(p)) p = Path.of(System.getProperty("user.dir"), "src/main/java", relative);
        assertTrue(Files.isRegularFile(p), "找不到源文件 " + relative + "（当前目录 " + new File(".").getAbsolutePath() + "）");
        return Files.readString(p);
    }

    /**
     * 冲突那一半（H6-2）：条件更新回 0 行就是"这一行不是你打开对话框时那一版了"。
     *
     * <p>要钉的是它<b>不写也不留档</b>：光看"没落库"不够，历史里多出一条从没生效过的版本，
     * 半年后有人照着它回滚，就把一个从未存在过的状态发给了玩家。
     */
    @Test
    void staleExpectIsRejectedWithoutTouchingAnything() {
        ContentItem before = row("element", "H", "{\"id\":\"H\",\"zh\":\"氢\"}", 1);
        ContentItem next = row("element", "H", "{\"id\":\"H\",\"zh\":\"氢-我的改动\"}", 1);
        when(content.updateIfUnchanged(eq(next), any())).thenReturn(0);
        // 冲突消息里要说得出"是谁在什么时候动的"，所以服务端会回读一次当前行
        ContentItem live = row("element", "H", "{\"id\":\"H\",\"zh\":\"氢-同事的改动\"}", 1);
        live.setUpdatedAt(LIVE_AT);
        live.setUpdatedBy("同事");
        when(content.get("element", "H")).thenReturn(live);

        BizException e = assertThrows(BizException.class,
                () -> svc.saveOver(before, next, "yunqi",
                        new Expect(Expect.Kind.UNCHANGED, THEN)));

        assertEquals(409, e.code, "面板按状态码分流到「载入最新」，200 + ok:false 那条形状在这里等于没有防线");
        assertEquals("CONFLICT", e.tag);
        assertTrue(e.getMessage().contains("element:H"), "得说清是哪一行，否则他下一步还是随便点开一条改");
        assertTrue(e.getMessage().contains("同事"));
        assertTrue(e.getMessage().contains("2026-10-06 05:20:11"), "给人看的时间是易读形状，不是 ISO 原文");
        verify(content, never()).upsert(any());
        verify(revs, never()).insert(any());
    }

    /** 条件成立时：先写、后留档。顺序反了的话，冲突那一次也会留下一条假历史。 */
    @Test
    void conditionalWriteGoesBeforeTheSnapshot() {
        ContentItem before = row("element", "H", "{\"id\":\"H\"}", 1);
        ContentItem next = row("element", "H", "{\"id\":\"H2\"}", 1);
        when(content.updateIfUnchanged(eq(next), any())).thenReturn(1);

        svc.saveOver(before, next, "yunqi",
                new Expect(Expect.Kind.UNCHANGED, THEN));

        InOrder order = inOrder(content, revs);
        order.verify(content).updateIfUnchanged(eq(next), eq(THEN));
        order.verify(revs).insert(any());
        verify(content, never()).upsert(any());
        verify(content, never()).insertOnly(any());
    }

    /** 新增撞上已有行：绝不退化成覆盖写（那是面板"我看到的列表里没有这一行"这个前提唯一的兑现处）。 */
    @Test
    void newExpectNeverFallsBackToUpsert() {
        ContentItem next = row("reaction", "R900", "{\"id\":\"R900\"}", 1);
        ContentItem live = row("reaction", "R900", "{\"id\":\"R900\",\"zh\":\"别人建的\"}", 1);
        live.setUpdatedAt(LIVE_AT);
        live.setUpdatedBy("同事");
        when(content.insertOnly(next)).thenThrow(new DuplicateKeyException("dup"));
        when(content.get("reaction", "R900")).thenReturn(live);

        BizException e = assertThrows(BizException.class, () -> svc.saveOver(null, next, "yunqi", Expect.NEW));
        assertEquals(409, e.code);
        assertTrue(e.getMessage().contains("建出来"), "这是「被人抢先建了」，不是「被人改了」，两句话得能分开");
        verify(content, never()).upsert(any());
        verify(revs, never()).insert(any());
    }

    /** 抢先建出来又删掉了：回读也是 null，这时候不能编一句"于 null 建出来"。 */
    @Test
    void duplicateOnAGoneRowSaysSoInsteadOfNamingNull() {
        ContentItem next = row("reaction", "R901", "{\"id\":\"R901\"}", 1);
        when(content.insertOnly(next)).thenThrow(new DuplicateKeyException("dup"));
        when(content.get("reaction", "R901")).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> svc.saveOver(null, next, "a", Expect.NEW));
        assertTrue(e.getMessage().contains("建出来又删掉"), e.getMessage());
        assertFalse(e.getMessage().contains("null"), e.getMessage());
    }

    /** 删、启停、回滚同样收版本号：漏一个入口，那个入口就变成"两个人互相覆盖而谁都没提示"的洞。 */
    @Test
    void everyEntryHonoursTheCondition() {
        ContentItem before = row("element", "H", "{\"id\":\"H\"}", 1);
        Expect stale = new Expect(Expect.Kind.UNCHANGED, THEN);
        when(content.deleteIfUnchanged("element", "H", stale.token())).thenReturn(0);
        when(content.setEnabledIfUnchanged(eq("element"), eq("H"), anyInt(), anyString(), eq(stale.token()))).thenReturn(0);
        when(content.updateIfUnchanged(any(), any())).thenReturn(0);

        assertThrows(BizException.class, () -> svc.deleteOver(before, "a", stale));
        assertThrows(BizException.class, () -> svc.toggleOver(before, 0, "a", stale));
        assertThrows(BizException.class, () -> svc.restoreOver(before, revision(1L, "{\"id\":\"H\"}"), "a", stale));

        verify(content, never()).delete(anyString(), anyString());
        verify(content, never()).setEnabled(anyString(), anyString(), anyInt(), anyString());
        verify(content, never()).upsert(any());
        verify(revs, never()).insert(any());
    }

    /** 三个"面对已存在的行"的入口不该接 {@code expect=none}：那句话在这里没有意义。 */
    @Test
    void noneIsOnlyMeaningfulForACreate() {
        ContentItem before = row("element", "H", "{\"id\":\"H\"}", 1);
        for (Runnable r : List.<Runnable>of(
                () -> svc.deleteOver(before, "a", Expect.NEW),
                () -> svc.toggleOver(before, 0, "a", Expect.NEW),
                () -> svc.restoreOver(before, revision(1L, "{\"id\":\"H\"}"), "a", Expect.NEW))) {
            BizException e = assertThrows(BizException.class, r::run);
            assertTrue(e.getMessage().contains("内部错误"), e.getMessage());
        }
        verify(content, never()).delete(anyString(), anyString());
        verify(content, never()).setEnabled(anyString(), anyString(), anyInt(), anyString());
        verify(content, never()).upsert(any());
    }

    /** 行已经不在了还要按条件回滚：这是"这一行不是你看到的那一版"的极端情况，别默默建回去。 */
    @Test
    void conditionalRollbackWithoutALiveRowIsAConflictNotACreate() {
        BizException e = assertThrows(BizException.class, () -> svc.restoreOver(null,
                revision(1L, "{\"id\":\"H\"}"), "a",
                new Expect(Expect.Kind.UNCHANGED, THEN)));
        assertEquals(409, e.code);
        verify(content, never()).upsert(any());
    }

    /**
     * 后台每个写内容的入口都必须收 {@code expect}（H6-2）。
     *
     * <p>这条是静态读源码而不是跑用例：漏掉的那个入口是下一次提交时才出现的，而跑到它的用例本身
     * 还不存在。判断只落在"这个方法调了落笔的那几个方法"上——将来有人加一个"批量上下架"却忘了
     * 带版本号，面板就会安静地退回"两个人互相覆盖、谁都没提示"的原点。
     */
    @Test
    void everyRowWriteEntryPointTakesAnExpect() throws Exception {
        String src = stripComments(sourceOf("com/chemera/server/controller/admin/AdminContentController.java"));
        for (String call : List.of("revisions.saveOver(", "revisions.deleteOver(",
                "revisions.toggleOver(", "revisions.restoreOver(")) {
            int at = src.indexOf(call);
            assertTrue(at >= 0, "AdminContentController 里找不到 " + call + "：四个写入口少了一个，乐观锁就少一扇门");
            String method = enclosingMethod(src, at);
            assertTrue(method.contains("String expect"),
                    call + " 所在的动作没收 expect 参数：" + firstLine(method));
            assertTrue(method.contains("Expect.parse(expect)"),
                    call + " 把 expect 读了就丢掉，没翻译成写入条件：" + firstLine(method));
        }

        String cfg = stripComments(sourceOf("com/chemera/server/controller/admin/AdminConfigController.java"));
        for (String call : List.of("config.upsert(", "config.insertOnly(", "config.updateIfUnchanged(",
                "config.deleteIfUnchanged(", "config.delete(")) {
            int at = cfg.indexOf(call);
            assertTrue(at >= 0, "AdminConfigController 里找不到 " + call + "（这一层形状变了，对账要跟着改）");
            String method = enclosingMethod(cfg, at);
            assertTrue(method.contains("expect") || method.contains("exp."),
                    call + " 所在的方法没收版本号：" + firstLine(method));
        }
    }

    /** 注释里提到这些名字是解释，不是调用；剥掉再找调用点（{@code (?s)} 是因为块注释跨行）。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, Math.min(nl, 120));
    }

    /** 取某个调用点所在的那个方法体（往上找最近的 {@code public} 声明，往下到配对的花括号）。 */
    private static String enclosingMethod(String src, int at) {
        int decl = src.lastIndexOf("public ", at);
        assertTrue(decl >= 0, "调用点之前找不到方法声明");
        int brace = src.indexOf('{', decl);
        int depth = 0, i = brace;
        for (; i < src.length(); i++) {
            if (src.charAt(i) == '{') depth++;
            else if (src.charAt(i) == '}') { depth--; if (depth == 0) { i++; break; } }
        }
        return src.substring(decl, i);
    }

    private static ContentRevision revision(long id, String data) {
        ContentRevision r = new ContentRevision();
        r.setId(id); r.setContentType("element"); r.setItemId("H");
        r.setName("旧名字"); r.setSort(7); r.setEnabled(1);
        r.setDataJson(data); r.setVersion(21L); r.setOperator("someone"); r.setSource("edit");
        return r;
    }
}
