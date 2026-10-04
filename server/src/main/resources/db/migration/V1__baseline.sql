-- 化学实验室：元素纪元 —— 基线库表 (MySQL 8/9, InnoDB, utf8mb4)
-- 设计：内容/配置用「索引列 + JSON 载荷」混合存储；用户/存档/会话/看板/审核用规范关系表。

-- ============ 游戏用户 ============
CREATE TABLE app_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)     NOT NULL,
  pass_hash     VARCHAR(100)    NOT NULL COMMENT 'BCrypt',
  nickname      VARCHAR(64)     NULL,
  status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0正常 1封禁',
  banned_until  DATETIME        NULL,
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at DATETIME        NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 登录会话（刷新 token，支持吊销）
CREATE TABLE user_session (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL,
  refresh_hash VARCHAR(80)     NOT NULL COMMENT '刷新token的SHA-256',
  device       VARCHAR(32)     NULL,
  issued_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at   DATETIME        NOT NULL,
  revoked_at   DATETIME        NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh (refresh_hash),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 云存档 ============
CREATE TABLE user_save (
  user_id     BIGINT UNSIGNED NOT NULL,
  payload     JSON            NOT NULL COMMENT '客户端存档全文',
  revision    BIGINT          NOT NULL DEFAULT 1 COMMENT '自增版本号，作冲突基线',
  updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 存档历史（回滚用，保留最近 N 份）
CREATE TABLE user_save_revision (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  revision   BIGINT          NOT NULL,
  payload    JSON            NOT NULL,
  source     VARCHAR(16)     NOT NULL DEFAULT 'upload' COMMENT 'upload/admin/rollback',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_rev (user_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 内容库（元素/化合物/反应/仪器/工艺/题库/房间/商店...）============
CREATE TABLE content_item (
  content_type VARCHAR(32)  NOT NULL COMMENT 'element/compound/reaction/instrument/process/quiz/room/shop/danger/...',
  item_id      VARCHAR(64)  NOT NULL COMMENT '业务主键，如 H / H2O / R001',
  name         VARCHAR(128) NOT NULL COMMENT '展示名(中文)，用于后台检索',
  sort         INT          NOT NULL DEFAULT 0,
  enabled      TINYINT      NOT NULL DEFAULT 1,
  data         JSON         NOT NULL COMMENT '完整记录（含 id）',
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by   VARCHAR(64)  NULL,
  PRIMARY KEY (content_type, item_id),
  KEY idx_type_sort (content_type, enabled, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 内容发布版本：客户端据此判断缓存是否失效
CREATE TABLE content_version (
  id         TINYINT      NOT NULL DEFAULT 1,
  version    BIGINT       NOT NULL DEFAULT 1,
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT ck_single CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO content_version (id, version) VALUES (1, 1);

-- ============ 运营配置（可热调参数：签到奖励/经济系数/商店/开关）============
CREATE TABLE app_config (
  cfg_key    VARCHAR(64)  NOT NULL,
  cfg_value  JSON         NOT NULL,
  category   VARCHAR(32)  NOT NULL DEFAULT 'general',
  remark     VARCHAR(255) NULL,
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by VARCHAR(64)  NULL,
  PRIMARY KEY (cfg_key),
  KEY idx_cat (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 管理员 ============
CREATE TABLE admin_user (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username   VARCHAR(64)     NOT NULL,
  pass_hash  VARCHAR(100)    NOT NULL,
  role       VARCHAR(16)     NOT NULL DEFAULT 'viewer' COMMENT 'super/editor/viewer',
  status     TINYINT         NOT NULL DEFAULT 0,
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 后台写操作审计
CREATE TABLE audit_log (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  admin      VARCHAR(64)     NOT NULL,
  action     VARCHAR(64)     NOT NULL COMMENT '如 content.update / user.ban',
  target     VARCHAR(128)    NULL,
  detail     JSON            NULL,
  ip         VARCHAR(64)     NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_admin_time (admin, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 数据看板 ============
CREATE TABLE analytics_event (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NULL,
  event      VARCHAR(64)     NOT NULL,
  props      JSON            NULL,
  day        DATE            NOT NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_event_day (event, day),
  KEY idx_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日汇总（看板加速，可由 /admin/api/dashboard/refresh 重算）
CREATE TABLE daily_stats (
  day        DATE         NOT NULL,
  dau        INT          NOT NULL DEFAULT 0,
  new_users  INT          NOT NULL DEFAULT 0,
  reactions  INT          NOT NULL DEFAULT 0,
  booms      INT          NOT NULL DEFAULT 0,
  trades     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 审核 ============
CREATE TABLE sensitive_word (
  id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  word   VARCHAR(64)     NOT NULL,
  level  TINYINT         NOT NULL DEFAULT 1 COMMENT '1提示 2拦截',
  PRIMARY KEY (id),
  UNIQUE KEY uk_word (word)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  reporter   BIGINT UNSIGNED NULL,
  target_user BIGINT UNSIGNED NULL,
  kind       VARCHAR(32)     NOT NULL COMMENT 'nickname/save/content',
  ref_id     VARCHAR(64)     NULL,
  reason     VARCHAR(512)    NULL,
  status     VARCHAR(16)     NOT NULL DEFAULT 'open' COMMENT 'open/handled/dismissed',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  handled_by VARCHAR(64)     NULL,
  PRIMARY KEY (id),
  KEY idx_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
