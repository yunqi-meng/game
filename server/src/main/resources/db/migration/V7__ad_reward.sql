-- V7：付费面改激励视频（TapADN / Dirichlet SSP）
-- 设计要点：奖励只由"服务端签发的工单 + 广告网络的服务器回调"决定，客户端一句话都说了不算。
--   1) 玩家点【看广告】→ ad.request 落一行 status=issued 的工单（带随机 ticket，写进 SDK 的 extra）；
--   2) 广告网络播完 → 回调 /api/ad/callback，验签通过才把工单置 rewarded（trans_id 唯一，天然幂等）；
--   3) 下一次取帧时 settle 把 rewarded 的行结算进存档并置 settled。
-- 过期没回调的工单由 expireStale 置 expired，不占玩家当日次数之外的任何额度。
CREATE TABLE ad_ticket (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ticket      CHAR(32)        NOT NULL COMMENT '服务端签发的随机串，作为 SDK extra 原样回传',
  user_id     BIGINT UNSIGNED NOT NULL,
  kind        VARCHAR(32)     NOT NULL COMMENT '广告位：boom/dbl/diamond/hint/revive/monthly …（app_config.ad.slots）',
  reward      VARCHAR(32)     NOT NULL COMMENT '奖励类型：coins/diamonds/hints/coupon/revive/monthly_days',
  amount      INT             NOT NULL DEFAULT 0 COMMENT '奖励数量，签发时定格，防运营中途改配',
  points      INT             NOT NULL DEFAULT 1 COMMENT '本次完成给的观看积分',
  status      VARCHAR(16)     NOT NULL DEFAULT 'issued' COMMENT 'issued/rewarded/settled/expired/void',
  trans_id    VARCHAR(64)     NULL COMMENT '广告网络交易号，幂等键',
  space_id    VARCHAR(64)     NULL COMMENT '该广告位的推广位 ID（TapADN spaceId）',
  issued_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at  DATETIME(3)     NOT NULL COMMENT '工单有效期，超时视为未完成',
  rewarded_at DATETIME(3)     NULL COMMENT '回调验签通过的时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ad_ticket (ticket),
  UNIQUE KEY uk_ad_trans (trans_id),
  KEY idx_ad_user_status (user_id, status),
  KEY idx_ad_status_expire (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='激励视频工单（签发→回调→结算）';

-- 广告经济参数（ slots 广告位 / unlocks 积分兑换 / 闸门 ）。
-- 只在缺失时写入：本地已跑过的库补上这一行，全新库由 V3 种子先行写入、这里保持不动，
-- 运营后来的编辑绝不会被迁移覆盖。真实语义见 ConfigSpec("ad")。
INSERT INTO app_config (cfg_key, cfg_value, category, remark, updated_by) VALUES
('ad',
 '{"enabled":true,"dailyTotal":16,"minLevel":1,"ticketTtlSec":900,"viewPoints":1,"slots":[{"kind":"boom","zh":"事故慰问金","desc":"炸锅之后回口血","reward":"coins","amount":500,"daily":2,"cooldownSec":300},{"kind":"dbl","zh":"双倍领取券","desc":"今日领取任务/成就奖励可翻倍","reward":"coupon","amount":1,"daily":1,"cooldownSec":0},{"kind":"diamond","zh":"钻石补给","desc":"服务器直接入账钻石","reward":"diamonds","amount":8,"daily":4,"cooldownSec":180},{"kind":"hint","zh":"精灵提示","desc":"助手精灵提示次数 +2","reward":"hints","amount":2,"daily":2,"cooldownSec":300},{"kind":"revive","zh":"挑战复活","desc":"换取 1 次复活（失败挑战 +2 步）","reward":"revive","amount":1,"daily":3,"cooldownSec":60},{"kind":"monthly","zh":"月卡时长","desc":"月卡 +1 天","reward":"monthly_days","amount":1,"daily":1,"cooldownSec":0}],"unlocks":[{"id":"elpack","zh":"镧系·锕系礼包","desc":"市场无视等级线全量解锁镧系/锕系","cost":20,"reward":"pack_el","amount":1,"once":true},{"id":"skin_cyber","zh":"皮肤·赛博纪元","desc":"深色霓虹实验室主题","cost":12,"reward":"skin","target":"cyber","once":true},{"id":"skin_retro","zh":"皮肤·复古炼金","desc":"暖棕黄铜的旧手册质感","cost":12,"reward":"skin","target":"retro","once":true},{"id":"monthly30","zh":"月卡·30 天","desc":"一次补足一个月月卡时长，可重复兑换","cost":25,"reward":"monthly_days","amount":30,"once":false}]}',
 'ad', 'AD', 'seed')
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;
