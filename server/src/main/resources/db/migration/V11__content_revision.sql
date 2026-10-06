-- V11：内容侧的历史与回滚（G3）
--
-- 症状：content_item 是覆盖写的，content_version 只是个自增计数器。458 行内容里改坏一行
-- （把某个反应的产率多打一个 0、把 quiz 的答案挪了一位），运营只能靠人肉回忆原来是什么，
-- 而"回忆"在正式服等于没有。用户存档侧从第一天起就有 user_save_revision 可以做历史和回滚，
-- 内容侧一直没有对应物——这是"运营敢不敢在正式服直接改内容"的分水岭。
--
-- 形状照 user_save_revision 抄，三处按内容库的实际情况改动：
--   • 主键维度是 (content_type,item_id) 而不是 user_id，所以索引带这两列；
--   • 除了 data_json，还把 name/sort/enabled 一起存下来。回滚必须能还原**整行**：
--     enabled 决定这一行会不会被下发（ContentMapper.allEnabled 筛 enabled=1），
--     sort 决定面板顺序，只还原 data 的话，回滚一次就把玩家看不到的一行留在禁用状态；
--   • version 存的是"落这一行时的 content_version"，不是行号。它让历史抽屉能和
--     客户端手里那个包对上号——出问题时"哪一版开始玩家拿到的就是这个数"是能回答的。
--
-- 语义是**覆盖前的快照（pre-image）**，与存档侧"写入后记一条"刻意不同：
-- 后台【历史】要回答的是"我上次改坏之前是什么样"，所以每行存的是"要被这次动作顶掉的那个版本"。
-- 代价是当前生效的那一版永远不在历史里（它就是 live 行本身），换来的是回滚目标一定是"曾经存在过的状态"，
-- 删除动作也能被还原（source=delete 那一行就是被删掉的内容全文）。
-- 见 ContentRevisionService.snapshot 与 AdminContentController 的三个写入点。
--
-- data_json 用 JSON 列而不是 TEXT：content_item.data 本来就是 JSON，两边同为 JSON 时
-- MySQL 的规范化（键序、空白）是同一套，回滚写回去的字节和当初写进来的字节才会逐字一致——
-- e2e 那条"改一行→回滚→玩家侧取到的 bundle 与改之前逐字相同"依赖的就是这一点。
-- 若是 TEXT 存原文、JSON 存新值，一次来回就多出格式差异，回归会红得看不出根因。
--
-- 容量：每个内容项只保留最近 50 版（ContentRevisionService.KEEP_PER_ITEM，写入后由两条
-- "先查第 50 新的号、再范围删"的语句修剪，理由和 SaveMapper.trimRevisionsBefore 一样：
-- 同一张表上的相关子查询 DELETE 会逼 InnoDB 自己再扫一遍）。一次内容改动落一行，
-- 50 版够回溯一整周的误操作，而这张表最坏情况是"458 行 × 50 版 × 每版几 KB"，
-- 真到嫌大再把上限挪进 app_config，不在这一版预设。
CREATE TABLE content_revision (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  content_type VARCHAR(32)  NOT NULL COMMENT '与 content_item.content_type 同域',
  item_id      VARCHAR(64)  NOT NULL COMMENT '与 content_item.item_id 同域',
  name         VARCHAR(128) NOT NULL DEFAULT '' COMMENT '覆盖前的展示名',
  sort         INT          NOT NULL DEFAULT 0 COMMENT '覆盖前的排序',
  enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '覆盖前的启用位：回滚要连它一起还原',
  data_json    JSON         NOT NULL COMMENT '覆盖前的完整记录',
  version      BIGINT       NOT NULL COMMENT '落这一行时的 content_version',
  operator     VARCHAR(64)  NOT NULL COMMENT '是哪次动作把这一版顶掉的（管理员名）',
  source       VARCHAR(16)  NOT NULL DEFAULT 'edit' COMMENT 'edit/delete/toggle/rollback',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_item (content_type, item_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容被覆盖前的快照：后台【历史】与单行回滚（G3）';
