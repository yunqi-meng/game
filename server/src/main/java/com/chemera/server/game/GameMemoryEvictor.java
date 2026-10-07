package com.chemera.server.game;

import com.chemera.server.service.AccountMemoryEvictor;
import org.springframework.stereotype.Component;

/**
 * 删号时把玩法侧留在内存里的现场一起带走（G6 的清单 + G7 的内存账）。
 *
 * <p>为什么删库之后还要清内存：{@code GameService} 的挑战临时台/沙盒/一次性题号和
 * {@code CurfewGuard} 的 minor 判定都是按 uid 躺在进程里的。人注销了，这些格子却还挂着——
 * 轻则"这个号没了但它的临时台还在内存里"，重则同名重建的新号撞上旧号那一格现场。
 *
 * <p>做成一个独立的 bean 而不是让 {@code AccountPurge} 直接依赖 {@code GameService}：
 * 后者已经牵着存档/广告/闸门一串 bean，让"删号"这条路去够它，等于把两个不该认识的模块焊在一起，
 * 也让 {@code AuthService} 的测试构造多两个假参数。接口只有一个方法，谁参与谁实现。
 */
@Component
public class GameMemoryEvictor implements AccountMemoryEvictor {

    private final GameService game;
    private final CurfewGuard curfew;

    public GameMemoryEvictor(GameService game, CurfewGuard curfew) {
        this.game = game; this.curfew = curfew;
    }

    @Override
    public void evict(long uid) {
        game.evict(uid);              // 每 uid 的引擎现场（挑战临时台 / 沙盒 / quizId）
        curfew.invalidate(uid);       // 青少年模式那次 minor 判定
    }
}
