-- 自动生成，勿手改。来源 tools/export-seed.mjs
SET NAMES utf8mb4;
INSERT INTO app_config (cfg_key, cfg_value, category, remark, updated_by) VALUES
('recharge', '[{"c":6,"d":60},{"c":30,"d":330},{"c":98,"d":1180}]', 'shop', 'RECHARGE', 'seed'),
('quality', '[{"q":0,"zh":"粗产物","mult":0.7},{"q":1,"zh":"纯产物","mult":1},{"q":2,"zh":"高纯产物","mult":1.5}]', 'progression', 'QUALITY', 'seed'),
('milestones', '[20,50,90,140,190]', 'progression', 'MILESTONES', 'seed'),
('tier_names', '["普通","精密","专业"]', 'progression', 'TIER_NAMES', 'seed'),
('tier_up_cost', '[3,8]', 'progression', 'TIER_UP_COST', 'seed'),
('lab_upgrades', '{"storage":{"zh":"储物柜扩容","desc":"每种物质库存上限 +50","step":50,"baseCost":800,"growth":1.6,"max":10},"safety":{"zh":"安全设施","desc":"事故损失与概率降低","step":1,"baseCost":1500,"growth":1.8,"max":5},"bench":{"zh":"工作台","desc":"合成成本预览更精准、批量倍率上限+5","step":1,"baseCost":2500,"growth":1.7,"max":5}}', 'progression', 'LAB_UPGRADES', 'seed'),
('discover_bonus', '{"1":[50,200],"2":[200,800],"3":[800,3000],"4":[3000,10000]}', 'economy', 'DISCOVER_BONUS', 'seed'),
('start_coins', '5000', 'economy', 'START_COINS', 'seed'),
('tutorial_coins', '2000', 'shop', 'TUTORIAL_COINS', 'seed'),
('quiz_reward', '120', 'progression', 'QUIZ_REWARD', 'seed'),
('sell_rate', '0.8', 'economy', 'SELL_RATE', 'seed'),
('buy_rate', '1.2', 'economy', 'BUY_RATE', 'seed'),
('first_sell_bonus', '1.5', 'economy', 'FIRST_SELL_BONUS', 'seed');
