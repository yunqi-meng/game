package com.chemera.server.common;

import java.util.List;

/**
 * 后台分页列表的唯一回体形状（H6-3）。
 *
 * <p>为什么要有这个类，而不是每个控制器各写一份 {@code Map.of("rows",…,"total",…)}：
 * 【审核】那两个列表以前回的是<b>裸 List</b>，面板拿不到总数，就在前端用本页长度<b>编</b>了一个
 * （{@code d.length < size ? 本页偏移 + d.length : 页码 * size + 1}）。那个数字看着像总数，
 * 于是分页组件永远显示"还有下一页"、最后一页报的数比实际多一条，而运营唯一的线索是页码不对劲。
 * 总数只有库知道，所以这一层把它和行一起带回去，并且<b>只有一个构造点</b>——
 * {@code test/admin-paging.js}（第 1 层裁判）据此对账：面板带 {@code off} 的每个端点，
 * 服务端必须回 {@code Page}，前端那个 {@code :total} 必须读的是服务端给的 {@code total}。
 *
 * <p>{@code total} 是 {@code long} 而不是 {@code Integer}：它来自 {@code COUNT(*)}，
 * 也就是"这个筛选条件下库里有几行"，与本页挑了几行无关。
 */
public record Page<T>(List<T> rows, long total) {

    /** 单页上限：这一条以前是每个控制器各写一遍 {@code Math.min(size, 200)}，【内容】那处写的是 500。 */
    public static final int MAX_SIZE = 200;

    public Page {
        // null 会被 Jackson 的 non_null 策略整个抹掉，面板那头读到 undefined 才知道坏了——
        // 那正是这一项要治的病，所以在这里就把它拦成异常，而不是发一个缺字段的响应。
        if (rows == null) throw new IllegalArgumentException("rows 不能为 null（空列表请用 List.of()）");
    }

    /**
     * 页长归一（H6-3）：缺省给 {@code dft}，上限统一收成 {@link #MAX_SIZE}。
     *
     * <p>上限不能省：这几个端点都能匿名不了、但都吃面板传来的任意数字，
     * {@code size=999999} 等于把整张表请进 JVM，正是 G5 那一层在治的病。
     */
    public static int size(Integer size, int dft) {
        if (size == null || size <= 0) return dft;
        return Math.min(size, MAX_SIZE);
    }

    /**
     * 偏移归一（H6-3）：负数在这里就收口成 0。
     *
     * <p>以前直接把请求参数喂进 {@code OFFSET}，一个手抖的 {@code off=-1}（面板改分页时写错、
     * 或有人拿 curl 试）会让 MySQL 报语法错，运营看到的是一句 500 内部错误——
     * 那既不说明问题也拦不住任何东西。第一页就是偏移 0，按这个意思解释它。
     */
    public static int off(Integer off) {
        return off == null || off < 0 ? 0 : off;
    }
}
