package com.chemera.server.game;

import java.util.Optional;

/**
 * 存档读写边界：把服务端权威 {@link GameState} 与持久层解耦，便于无 DB 单元测试注入内存实现。
 * 唯一生产实现 {@code DbGameStore} 走 SaveService（revision 乐观并发 + 校验 + 裁剪）。
 */
public interface GameStore {
    /** 载入某用户存档；不存在返回 empty（首登/游客首帧由上层用 {@link GameState#fresh} 兜底）。 */
    Optional<GameState> load(long uid);

    /** 权威写回：序列化 GameState → payload，force 覆盖，返回新的 revision。 */
    long save(long uid, GameState g);
}
