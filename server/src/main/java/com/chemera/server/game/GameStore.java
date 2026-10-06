package com.chemera.server.game;

import java.util.Optional;

/**
 * 存档读写边界：把服务端权威 {@link GameState} 与持久层解耦，便于无 DB 单元测试注入内存实现。
 * 唯一生产实现 {@code DbGameStore} 走 SaveService（revision 乐观并发 + 校验 + 裁剪）。
 */
public interface GameStore {
    /** {@link #saveCas} 判到冲突时的返回值：调用方什么都没写出去，应当重读重放。 */
    long CONFLICT = -1L;

    /** 一帧存档 + <b>它落库时的</b> revision：两者必须同读同写，见 {@link #loadFrame}。 */
    record Frame(GameState state, long revision) { }

    /**
     * 载入某用户存档；不存在返回 empty（首登/游客首帧由上层用 {@link GameState#fresh} 兜底）。
     *
     * <p>注意这条路径拿不到行号，意图链不要用它在写回前定位版本——那正是丢帧的来路，见 {@link #loadFrame}。
     */
    default Optional<GameState> load(long uid) {
        return loadFrame(uid).map(Frame::state);
    }

    /**
     * 权威读帧：状态与 revision <b>必须出自同一次读</b>。
     *
     * <p>如果先 {@code load} 再单独查一次号，两次 SELECT 之间别人写进去的那一帧就会"借"到我们的号上：
     * CAS 比对通过，我们却拿自己那份旧状态覆盖了他。窗口只有一瞬，但它错得比没有 CAS 更隐蔽——
     * 日志里全绿，玩家的东西没了。所以号只能跟着帧一起出来。
     */
    Optional<Frame> loadFrame(long uid);

    /** 无条件写回（覆盖）：只给后台改档、存档导入这类"不跟人抢"的入口用。 */
    long save(long uid, GameState g);

    /**
     * 乐观并发写回：仅当库里仍是 {@code expected} 这一帧时才落盘。
     * 返回新 revision；被判冲突返回 {@link #CONFLICT}，此时库里一个字都没动。
     *
     * <p>{@code source} 会进 {@code user_save_revision}，见 SaveService 的历史抽样口径。
     */
    long saveCas(long uid, GameState g, long expected, String source);
}
