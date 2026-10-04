-- 迭代 2 · A5：管理员账号可后台管理（此前 admin_user 有表无接口，超管改密只能手写 SQL）
-- must_change_password：由后台建号或种子弱默认口令触发，置 1 时该管理员只能访问"改自己的密码"，
-- 其余后台端点一律 403，直到口令换掉为止。
ALTER TABLE admin_user
  ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0
    COMMENT '1=下次登录必须先改密（初始口令/被重置）',
  ADD COLUMN updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    COMMENT '口令或角色最后一次变更';
