package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.common.Expect;
import com.chemera.server.entity.ContentItem;
import com.chemera.server.entity.ContentRevision;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.ContentRevisionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容历史与回滚（G3）：覆盖写之前，先把"要被顶掉的那一版"存下来。
 *
 * <p>为什么要有这一层而不把 INSERT 直接写在控制器里：三个写入口（改内容、删内容、启停）
 * 加一个回滚动作都要落同一份快照，字段取值口径必须一致——漏一个入口，那个入口就变成
 * "改坏了查不到"的洞，而它恰恰是最容易漏的（启停按钮不碰 data，最容易写成"不用记历史"）。
 * 控制器只负责"从哪儿拿到旧行"，这里负责"存成什么样、留多少"。
 *
 * <p>历史行是<b>覆盖前的状态</b>（pre-image），所以当前生效那一版不在历史里——它就是 live 行本身。
 * 每条 {@code source} 记的是"把它顶掉的动作"，不是"这一版当初怎么来的"：抽屉里读的是
 * "2026-10-06 05:20 被 admin 的一次 edit 覆盖掉了"，运营要的是这句话。
 *
 * <p>启停为什么也要落一行：{@code enabled} 决定这一行会不会被 {@code ContentMapper.allEnabled}
 * 下发，误关一个元素等于玩家图鉴里凭空少一格，而这件事和改错产率一样需要能退回。
 *
 * <p>H6-2 之后这一层还多担一件事：它是内容写入库的<b>唯一落笔点</b>，所以乐观锁（{@link Expect}）
 * 也在这里判。放在这里是刻意的——四个写入口（改、删、启停、回滚）漏一个，那个入口就变成
 * "两个运营互相覆盖、谁都没收到任何提示"的洞，而漏的那个通常是最晚加进来的那个。
 */
@Service
public class ContentRevisionService {

    /** 每个内容项保留的历史条数。上限的理由和容量估算都写在 V11 的头注释里。 */
    public static final int KEEP_PER_ITEM = 50;

    /** 顶掉旧行的动作是闭合集合：{@code source} 列只有 16 字符，写错一个字母历史就看不懂了。 */
    static final Set<String> SOURCES = Set.of("edit", "delete", "toggle", "rollback");

    private final ContentRevisionMapper revs;
    private final ContentMapper content;

    public ContentRevisionService(ContentRevisionMapper revs, ContentMapper content) {
        this.revs = revs;
        this.content = content;
    }

    /**
     * 覆盖写：先记下旧行，再写新行。
     *
     * <p>两句必须在同一个事务里（{@code ContentRevisionServiceTest.saveOverIsTransactional} 钉这条）：
     * 拆开写的失败长相是"历史里多了一条从没生效过的版本，而 live 行还是旧的"——半年后有人照着
     * 那条历史回滚，就把一个从未存在过的状态发给了玩家。快照必须在写<b>之前</b>取，
     * 事后 {@code content.get} 读回来的是新行，历史会退化成"当前版本的复读"。
     */
    @Transactional
    public void saveOver(ContentItem before, ContentItem next, String operator) {
        saveOver(before, next, operator, Expect.FORCE);
    }

    /**
     * 带条件的覆盖写（H6-2）。两个运营同时改同一条反应，后提交的那个不该静默盖掉前一个——
     * 这一层是唯一的落笔点，所以判断放在这里，而不是让每个调用方自己记得先读一遍。
     *
     * <ul>
     *   <li>{@link Expect.Kind#FORCE}：老行为，无条件 upsert（导入脚本走这条）。</li>
     *   <li>{@link Expect.Kind#NEW}：这一行必须还不存在。撞上主键就 409，绝不退化成 upsert。</li>
     *   <li>{@link Expect.Kind#UNCHANGED}：{@code updated_at} 必须还是面板拿到的那一版。
     *       条件更新<b>先于</b>快照执行：没写进去就不该在历史里多出一条从没生效过的版本
     *       （整个方法在一个事务里，抛 {@link BizException} 也会回滚，两重保险）。</li>
     * </ul>
     */
    @Transactional
    public void saveOver(ContentItem before, ContentItem next, String operator, Expect exp) {
        next.setUpdatedBy(operator);
        switch (exp.kind()) {
            case FORCE -> {
                snapshot(before, operator, "edit");
                content.upsert(next);
            }
            case NEW -> {
                try {
                    content.insertOnly(next);
                } catch (DuplicateKeyException e) {
                    throw created(label(next), fetched(next));
                }
            }
            case UNCHANGED -> {
                if (content.updateIfUnchanged(next, exp.requireToken()) == 0)
                    throw stale(label(next), fetched(next));
                snapshot(before, operator, "edit");
            }
        }
    }

    /** 删除：被删掉的内容全文就存在这一行历史里，所以删错了还能整行还原回来。 */
    @Transactional
    public void deleteOver(ContentItem before, String operator) {
        deleteOver(before, operator, Expect.FORCE);
    }

    /** 条件删除（H6-2）：别人在你打开列表之后改过这一行，就不该由你把它删掉。 */
    @Transactional
    public void deleteOver(ContentItem before, String operator, Expect exp) {
        if (before == null) return;
        requireNotNew(exp);
        if (exp.kind() == Expect.Kind.UNCHANGED) {
            if (content.deleteIfUnchanged(before.getContentType(), before.getItemId(), exp.requireToken()) == 0)
                throw stale(label(before), fetched(before));
            snapshot(before, operator, "delete");   // 条件已经成立，这一行确实是被这次动作删掉的
            return;
        }
        snapshot(before, operator, "delete");
        content.delete(before.getContentType(), before.getItemId());
    }

    /** 启停：{@code enabled} 决定这一行会不会被下发，误关一次和改坏一次一样要能退。 */
    @Transactional
    public void toggleOver(ContentItem before, int enabled, String operator) {
        toggleOver(before, enabled, operator, Expect.FORCE);
    }

    /** 条件上下架（H6-2）：列表里那个开关同样不能盖掉别人刚做的改动。 */
    @Transactional
    public void toggleOver(ContentItem before, int enabled, String operator, Expect exp) {
        if (before == null) return;
        requireNotNew(exp);
        if (exp.kind() == Expect.Kind.UNCHANGED) {
            if (content.setEnabledIfUnchanged(before.getContentType(), before.getItemId(),
                    enabled, operator, exp.requireToken()) == 0)
                throw stale(label(before), fetched(before));
            snapshot(before, operator, "toggle");
            return;
        }
        snapshot(before, operator, "toggle");
        content.setEnabled(before.getContentType(), before.getItemId(), enabled, operator);
    }

    /** 回滚：把 live 行当前状态存成历史（source=rollback），再把选中的那一版写回去。 */
    @Transactional
    public void restoreOver(ContentItem before, ContentRevision chosen, String operator) {
        restoreOver(before, chosen, operator, Expect.FORCE);
    }

    /**
     * 条件回滚（H6-2）：回滚写的是 live 行，所以它也该守同一个规矩——
     * 否则"看见冲突先回滚一版试试"就成了绕过乐观锁的门。
     */
    @Transactional
    public void restoreOver(ContentItem before, ContentRevision chosen, String operator, Expect exp) {
        requireNotNew(exp);
        ContentItem it = toContentItem(chosen);
        it.setUpdatedBy(operator);
        if (exp.kind() == Expect.Kind.UNCHANGED) {
            if (before == null) throw BizException.staleWrite(label(it), null, null);
            if (content.updateIfUnchanged(it, exp.requireToken()) == 0)
                throw stale(label(it), fetched(it));
            snapshot(before, operator, "rollback");
            return;
        }
        snapshot(before, operator, "rollback");
        content.upsert(it);
    }

    /**
     * 冲突文案里的"哪一行"：类型 + 业务主键，运营在列表里一眼能对上。
     * 只说"内容项"不够——他得知道是哪一条，否则下一步还是随便点开一条改。
     */
    private static String label(ContentItem it) {
        return "内容项「" + it.getContentType() + ":" + it.getItemId() + "」";
    }

    /** 条件失败之后回读当前行：消息里要说得出"是谁在什么时候动了它"，而那一刻它已经变了。 */
    private ContentItem fetched(ContentItem it) {
        return content.get(it.getContentType(), it.getItemId());
    }

    /** {@code cur} 为空表示这一行已被删除，出路和"被人改过"不同，交给 {@link BizException#staleWrite} 分流。 */
    private static BizException stale(String what, ContentItem cur) {
        return BizException.staleWrite(what, cur == null ? null : cur.getUpdatedAt(),
                cur == null ? null : cur.getUpdatedBy());
    }

    private static BizException created(String what, ContentItem cur) {
        return BizException.createdAfter(what, cur == null ? null : cur.getUpdatedAt(),
                cur == null ? null : cur.getUpdatedBy());
    }

    /** {@code expect=none} 只对"新增"有意义：删/启停/回滚面对的都是已经存在的行。 */
    private static void requireNotNew(Expect exp) {
        if (exp != null && exp.kind() == Expect.Kind.NEW)
            throw new BizException("内部错误：这一种写入不需要「这行还不存在」的前提");
    }

    /**
     * 落一行"旧版本"。{@code before} 为空表示这次是新建（没有旧行可存），静默跳过而不是报错——
     * 建档本来就没有"上一版"，为它编一个空快照只会污染历史。
     */
    void snapshot(ContentItem before, String operator, String source) {
        if (before == null || before.getData() == null || before.getData().isBlank()) return;
        if (!SOURCES.contains(source)) throw new BizException("内部错误：未知的历史来源 " + source);

        ContentRevision r = new ContentRevision();
        r.setContentType(before.getContentType());
        r.setItemId(before.getItemId());
        r.setName(before.getName() == null ? "" : before.getName());
        r.setSort(before.getSort() == null ? 0 : before.getSort());
        r.setEnabled(before.getEnabled() == null ? 1 : before.getEnabled());
        r.setDataJson(before.getData());
        // 此刻的 content_version 就是玩家手里那个包的对账号；bumpVersion 是写完之后的事
        r.setVersion(content.version());
        r.setOperator(operator);
        r.setSource(source);
        revs.insert(r);
        trim(before.getContentType(), before.getItemId());
    }

    /**
     * 修剪到最近 {@link #KEEP_PER_ITEM} 版：先查出"第 50 新"的号，再把更早的范围删掉。
     *
     * <p>没攒到 50 版时 {@code floorId} 返回 null，一条 DELETE 都不发。两步走而不是
     * {@code DELETE ... WHERE id < (子查询同一张表)}，形状与理由同 {@code SaveMapper.trimRevisionsBefore}。
     */
    private void trim(String type, String id) {
        Long floor = revs.floorId(type, id, KEEP_PER_ITEM - 1);
        if (floor != null) revs.deleteOlder(type, id, floor);
    }

    /** 【历史】抽屉：只有元数据，没有整份记录（{@code MapperQueryHygieneTest} 钉着这条）。 */
    public List<Map<String, Object>> history(String type, String id, int limit) {
        return revs.listMeta(type, id, Math.max(1, Math.min(limit, KEEP_PER_ITEM)));
    }

    /** 取一条历史全文（回滚用）。找不到就 404，别让前端拿到 null 再去猜。 */
    public ContentRevision require(long id) {
        ContentRevision r = revs.get(id);
        if (r == null) throw BizException.notFound("历史版本");
        return r;
    }

    /**
     * 把一条历史还原成"可以写回 content_item 的那一行"。
     *
     * <p>只搬内容行的字段：{@code operator} 用回滚那次动作的人（控制器那边填），
     * {@code updated_at} 交给库的 {@code ON UPDATE}。历史行的 id/version/source 不属于内容表，
     * 带过去只会让 {@code ContentItem} 多出三个没人读的字段。
     */
    public ContentItem toContentItem(ContentRevision r) {
        ContentItem it = new ContentItem();
        it.setContentType(r.getContentType());
        it.setItemId(r.getItemId());
        it.setName(r.getName());
        it.setSort(r.getSort());
        it.setEnabled(r.getEnabled());
        it.setData(r.getDataJson());
        return it;
    }
}
