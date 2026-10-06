-- V9：埋点采集的合规开关（隐私政策里"你可以要求停止收集行为数据"这句话的兑现点）
-- 设计要点：
--   1) 默认开=保持现行行为不变。这一行只是把开关摆到运营看得见的位置，种一个 true 不是改变任何人的数据流。
--   2) 关停的效果是"新事件不再入库"，接口仍回 200：客户端不该因为采集停了看到错误，更不该为此发版
--      （H5 与已装出去的安卓包共用这一条语义，所以判定只能在服务端做）。
--   3) 已入库的历史记录不由这个开关删除——那要走独立的 DB 清理，不能藏在一个布尔里。
-- 只在缺失时写入：本地已跑过的库补上这一行，运营后来的编辑绝不会被迁移覆盖。语义见 ConfigSpec("analytics_enabled")。
INSERT INTO app_config (cfg_key, cfg_value, category, remark, updated_by) VALUES
('analytics_enabled', 'true', 'compliance', 'ANALYTICS', 'seed')
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;
