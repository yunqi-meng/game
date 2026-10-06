-- V10：后台查询里的四处扫（G5）
--
-- 1) app_user 只有主键与 uk_username 两个索引，而看板的"今日活跃""今日新增"每天都按
--    last_login_at / created_at 筛整张表（AnalyticsMapper.dauToday / newUsersToday / statRow）。
--    这两列都是"写一次读每天"，加索引的代价只在登录那一下，收益在运营的每一次刷新。
--
-- 2) 同一批查询的谓词得跟着改形状：DATE(last_login_at)=CURDATE() 把列包在函数里，
--    优化器用不上刚建的索引（非 sargable）。改成 col >= CURDATE() AND col < CURDATE()+1天 的区间，
--    两边对同一天的定义必须严格等价——改这里时对照 DailyStatsJobTest 的那几条断言。
--    见 AnalyticsMapper。
--
-- 3) report.kind 的列注释还停在 'nickname/save/content'，而真正闭合的集合是
--    ReportService.KINDS = nickname/listing/behavior/other。注释留着旧的，下一个读 DDL 的人
--    会照着它再加一套校验，于是"哪些举报类型合法"就有了两个答案。这里同步措辞，不动数据。
--
-- 4) 明确没修的一处：后台的用户检索是 LIKE '%q%'，前导通配符天生用不了索引。
--    这一版把 count 与 page 的 WHERE 统一成同一个常量（见 UserMapper.LIST_FILTER），
--    扫是慢，但"总数"和列表从今往后是同一个集合——错了的数比慢的数危害大。
--    真要做全文检索再上 ngram 索引，不在这次射程里。
ALTER TABLE app_user ADD KEY idx_last_login (last_login_at);
ALTER TABLE app_user ADD KEY idx_created (created_at);

ALTER TABLE report
    MODIFY COLUMN kind VARCHAR(32) NOT NULL
    COMMENT 'nickname/listing/behavior/other（闭合集合与校验见 ReportService.KINDS）';
