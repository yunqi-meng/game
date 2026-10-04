-- V4：游客身份支持（在线化改造：强制登录 + 服务端保存的游客试玩）
-- 附加列，向后兼容；现有账号 is_guest=0。
ALTER TABLE app_user ADD COLUMN is_guest TINYINT NOT NULL DEFAULT 0;
CREATE INDEX idx_user_guest ON app_user (is_guest);
