package com.chemera.server.service;

/**
 * 删号之后要顺手清掉的"进程内内存现场"（G6 + G7）。
 *
 * <p>为什么是一个接口而不是直接把 {@code GameService} 注进 {@link AccountPurge}：
 * 这一头的调用方是"删账号"，那一头是"跑玩法"，两者本来不该认识。直接依赖会把
 * {@code service → game} 的编译边拉成一条随时可能成环的长链（{@code GameService} 那边已经牵着
 * 存档、广告、闸门一串 bean），而 {@code AuthService} 的测试构造还得凭空造一个假 GameService。
 * 接口只有一句话："这个人没了，把他留在各处的内存态抹掉"，谁想参与就自己实现一个 bean，
 * 容器把所有实现注进来，不想要内存清理的装配（老单测）拿 {@link #NONE} 就行。
 *
 * <p>{@link #NONE} 是空实现：内存现场没清只是"这个 uid 的临时台还躺在内存里"，
 * 等它自己的闲置清扫收尾（G7 的上界与 TTL），不影响库里已经删干净这件事。
 */
public interface AccountMemoryEvictor {

    /** 账号被删除：请丢掉与这一位有关的全部进程内状态。实现必须幂等、不许抛。 */
    void evict(long uid);

    /** 空实现：给没有内存现场可清（或单测不想牵进玩法 bean）的装配用。 */
    AccountMemoryEvictor NONE = uid -> { };
}
