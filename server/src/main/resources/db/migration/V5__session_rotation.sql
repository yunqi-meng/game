-- 刷新令牌轮换（A1）：区分"主动注销"与"轮换作废"两种撤销。
-- revoked_at  = 玩家退出/改密/注销/泄露处置，属硬撤销，令牌再出现即拒绝；
-- rotated_at  = 刷新时被新令牌替换，允许极短的宽限以容忍多标签页并发刷新。
ALTER TABLE user_session
  ADD COLUMN rotated_at DATETIME NULL COMMENT '被轮换掉的时刻（区别于主动撤销）',
  ADD KEY idx_rotated (rotated_at);
