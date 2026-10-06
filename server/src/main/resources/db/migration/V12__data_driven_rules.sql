-- V12：把引擎里最后几个写死的结算参数与成就判定搬进库（G4）
--
-- 症状：后台 13 类内容表单什么都能配，唯独两件事配不动——
--   1) 成就的"达成条件"。判定写在 GameEngine.achDone 的 19 路 switch 里，
--      于是新增一行成就而不动这段 Java，它就永远判未达成，而面板看起来一切正常。
--   2) 升级曲线、单容器物质种数、事故概率与赔付、答题年级倍率。它们当时是
--      Content.levelExp / GameEngine.MAX_LINES / GameEngine 里那串数字 / EconomyService.GRADE_MULT，
--      调一档就要发一次服务端版本。
--
-- 这一版之后它们的真源是 app_config 与 content_item，读法见 ConfigSpec 对应条目，
-- 存前边界见 EngineConfigValidator，成就词表见 AchievementRule.Metric（闭集，念不出来的名字当场拒）。
--
-- 刻意保持"迁移当天没有任何玩家结算发生变化"：种进去的值与原来写死的字面量逐字相同
-- （level_exp 80/25 == 旧的 80 + lv²×25；accident 十四个数 == 旧的 0.5/0.08/0.1/0.3/0.05/0.04/
-- 0.9/0.12/0.5/100/2/10/0.2/0.5；quiz_grade_mult == 旧 GRADE_MULT；bench_max_lines 6 == 旧 MAX_LINES）。
-- 由 ConfigSpecTest 与 EngineConfigValidatorTest 把这些默认值钉住，改一边就会红另一边。
--
-- updated_by 用 'v12' 而不是 'seed'：让后台列表上一眼看出这一行是哪个版本带进来的，
-- 出问题时"是运营改的还是迁移种的"有据可查。

-- ---------- 1. 四个结算参数键（ON DUPLICATE 保空：本地已被运营改过的值绝不覆盖回来） ----------
INSERT INTO app_config (cfg_key, cfg_value, category, remark, updated_by) VALUES
('level_exp', '{"base":80,"coef":25}', 'progression', 'LEVEL_EXP', 'v12'),
('bench_max_lines', '6', 'progression', 'BENCH_MAX_LINES', 'v12'),
('accident', '{"hit_base":0.5,"hit_floor":0.08,"safety_step":0.1,"danger_base":0.3,"danger_floor":0.05,"danger_step":0.04,"loss_base":0.9,"loss_step":0.12,"loss_ratio":0.5,"repair_base":100,"repair_exp_mult":2,"repair_exp_floor":10,"protect_mult":0.2,"insured_refund":0.5}', 'economy', 'ACCIDENT', 'v12'),
('quiz_grade_mult', '{"小学":1.0,"初中":1.0,"高中":1.2,"大学":1.5}', 'progression', 'QUIZ_GRADE_MULT', 'v12')
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;

-- ---------- 2. 19 条成就的达成条件回填 ----------
-- 与 AchievementRule.LEGACY 一一对应（那份白名单留着是为了"迁移没跑到的库也判得对"，
-- 而这里的 cond 才是运营能改的那一份：行里写了 cond 就以行为准）。
-- 条件里带 JSON_CONTAINS_PATH 判断：谁已经在后台自己配过条件，就不覆盖它。
UPDATE content_item SET data = JSON_SET(data, '$.cond', CAST(CASE item_id
    WHEN 'aFirst'     THEN '{"metric":"success","op":"ge","value":1}'
    WHEN 'aWater'     THEN '{"metric":"discoveredSubstance","subject":"H2O"}'
    WHEN 'aGold'      THEN '{"metric":"discoveredSubstance","subject":"Au"}'
    WHEN 'aBoom'      THEN '{"metric":"boom","op":"ge","value":1}'
    WHEN 'aS100'      THEN '{"metric":"success","op":"ge","value":100}'
    WHEN 'aD20'       THEN '{"metric":"discoveredCount","op":"ge","value":20}'
    WHEN 'aD80'       THEN '{"metric":"discoveredCount","op":"ge","value":80}'
    WHEN 'aD200'      THEN '{"metric":"discoveredCount","op":"ge","value":200}'
    WHEN 'aEq30'      THEN '{"metric":"reactionsKnownCount","op":"ge","value":30}'
    WHEN 'aLv10'      THEN '{"metric":"level","op":"ge","value":10}'
    WHEN 'aLv20'      THEN '{"metric":"level","op":"ge","value":20}'
    WHEN 'aRich'      THEN '{"metric":"coins","op":"ge","value":50000}'
    WHEN 'aOrganic'   THEN '{"metric":"discoveredSubstance","subject":"CH3COOC2H5"}'
    WHEN 'aAqua'      THEN '{"metric":"discoveredSubstance","subject":"aqua_regia"}'
    WHEN 'aQuiz50'    THEN '{"metric":"quizOk","op":"ge","value":50}'
    WHEN 'aSnake'     THEN '{"metric":"knownReaction","subject":"R141"}'
    WHEN 'aRep'       THEN '{"metric":"reputation","op":"ge","value":50}'
    WHEN 'aChallenge' THEN '{"metric":"challenges","op":"ge","value":1}'
    WHEN 'aSandbox'   THEN '{"metric":"sandbox","op":"ge","value":5}'
    END AS JSON))
WHERE content_type = 'achievement'
  AND item_id IN ('aFirst','aWater','aGold','aBoom','aS100','aD20','aD80','aD200','aEq30','aLv10','aLv20',
                  'aRich','aOrganic','aAqua','aQuiz50','aSnake','aRep','aChallenge','aSandbox')
  AND enabled = 1
  AND JSON_CONTAINS_PATH(data, 'one', '$.cond') = 0;

-- ---------- 3. 内容版本 +1：条件跟着 bundle 下发给客户端，玩家侧要能看到 ----------
UPDATE content_version SET version = version + 1 WHERE id = 1;
