package com.chemera.server.game;

/**
 * 引擎的会话级瞬态上下文（不入库）：挑战/沙盒临时台、沙盒开关、实验保险、批量倍率。
 * 常驻的实验台数组与当前索引保存在 GameState.benchStates / GameState.bi。
 */
public class EngineCtx {
    public GameState.Bench tempBench = null;   // 挑战/沙盒临时工作台
    public boolean sandboxActive = false;
    public boolean insured = false;             // 单次实验保险（事故后失效）
    /** 本轮下发给客户端的题目 id：只此题可作答，答完即焚，防止客户端穷举选项刷奖励。 */
    public String quizId = null;
    public int multiplier = 1;                  // 批量倍率

    public EngineCtx() {}

    public static EngineCtx normal() { return new EngineCtx(); }
}
