package com.chemera.server.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 乐观锁那个前提本身的翻译规则（H6-2）。
 *
 * <p>{@code expect} 是从 URL 查询串进来的一个字符串，而它决定这次写入是"覆盖"、"只建"还是
 * "只在这一行没被人动过时才写"。这一步判错，后面 SQL 里的条件再严也没用：把面板抄来的版本号
 * 解析成别的时间点，每个运营都会在自己的对话框里天天撞冲突。
 */
class ExpectTest {

    @Test
    void absentMeansUnconditionalOverwrite() {
        assertTrue(Expect.parse(null).isForce());
        assertTrue(Expect.parse("").isForce());
        assertTrue(Expect.parse("   ").isForce(), "空白也算没带：浏览器有时把空参数发成 ?expect=");
    }

    @Test
    void noneMeansThisRowMustNotExistYet() {
        Expect e = Expect.parse("none");
        assertTrue(e.isNew());
        assertEquals(Expect.Kind.NEW, e.kind());
    }

    /** 面板回传的就是列表里那份原文，所以这里必须吃得下带毫秒的 ISO，也必须吃得下没有毫秒的那一份。 */
    @Test
    void timestampBecomesAnUnchangedCondition() {
        assertEquals(LocalDateTime.of(2026, 10, 6, 5, 20, 11, 123_000_000),
                Expect.parse("2026-10-06T05:20:11.123").token());
        assertEquals(LocalDateTime.of(2026, 10, 6, 5, 20, 11),
                Expect.parse("2026-10-06T05:20:11").token(), "毫秒为 0 时 Jackson 不写小数位，回来还是同一个号");
        assertEquals(Expect.Kind.UNCHANGED, Expect.parse(" 2026-10-06T05:20:11.123 ").kind());
    }

    /**
     * 解析不了是 400，不是 409。
     *
     * <p>这两件事在面板上是两个不同的弹窗：409 会说"是谁在什么时候动了它"并给「载入最新」；
     * 而"expect 里躺着一句中文"是请求本身写坏了（手拼 URL、字段拼错），给出冲突弹窗只会让人
     * 以为同事跟他抢这一行。
     */
    @Test
    void unparsableExpectIsABadRequestNotAConflict() {
        BizException e = assertThrows(BizException.class, () -> Expect.parse("昨天下午"));
        assertEquals(400, e.code);
        assertTrue(e.getMessage().contains("昨天下午"), "报错要带上那个解析不了的原文，否则排查全靠猜");
        assertTrue(e.getMessage().contains("updatedAt"), "报错要指出去哪儿抄正确的格式");
    }

    /** FORCE/NEW 没有时间点可取：调用方拿错就是编程错误，别让它静默带着 null 进 SQL。 */
    @Test
    void tokenIsOnlyAvailableForTheUnchangedCondition() {
        assertThrows(BizException.class, () -> Expect.FORCE.requireToken());
        assertThrows(BizException.class, () -> Expect.NEW.requireToken());
    }
}
