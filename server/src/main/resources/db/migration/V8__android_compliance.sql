-- V8：安卓上架要用的三件合规/发布基建（防沉迷时段、APP 版本门、TapTap 身份绑定）
-- 设计要点：
--   1) 防沉迷是**账号属性 + 服务端时段判定**，不是前端灰按钮。本作没有接实名认证（那是独立资质与接口），
--      所以"谁是未成年人"有两个可信来源：玩家在登录页自行开启青少年模式，或运营在后台【用户管理】里标记。
--      命中后 GameService 在任何玩法意图之前拒绝，客户端只负责把服务端给的窗口倒计时画出来。
--   2) APP 版本门让服务端能硬拒老包：低于 minBuild 必须更新，低于 latestBuild 建议更新。
--      没有这一条，旧客户端配新协议会打出无从解释的错误，上架后被驳回就是几天。
--   3) TapTap 登录主体落在 app_user.taptap_open_id（可空 + 唯一）：一个第三方身份只对应一个本服账号，
--      重复登录走同一条建档/并档路径。
ALTER TABLE app_user
  ADD COLUMN minor          TINYINT     NOT NULL DEFAULT 0 COMMENT '青少年模式：1=未成年人，受 app_config.curfew 时段闸门约束',
  ADD COLUMN taptap_open_id VARCHAR(64) NULL COMMENT 'TapTap 登录主体标识，NULL=未绑定第三方',
  ADD UNIQUE KEY uk_taptap_open (taptap_open_id);

-- 防沉迷时段与 APP 版本门两项配置。只在缺失时写入：本地已跑过的库补上这两行，
-- 运营后来的编辑绝不会被迁移覆盖。真实语义见 ConfigSpec("curfew") / ConfigSpec("app_version")。
INSERT INTO app_config (cfg_key, cfg_value, category, remark, updated_by) VALUES
('curfew',
 '{"enabled":true,"zone":"Asia/Shanghai","days":[5,6,7],"from":"20:00","to":"21:00","extraDates":[],"hint":"未成年人仅在周五、周六、周日及法定节假日的 20:00-21:00 可游玩"}',
 'compliance', 'CURFEW', 'seed'),
('app_version',
 '{"minBuild":1,"latestBuild":1,"note":"","url":""}',
 'app', 'APP_VERSION', 'seed')
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;
