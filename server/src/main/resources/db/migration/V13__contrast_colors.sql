-- V13：把四个"无论配白字还是配黑字都读不清"的物质色挪进 WCAG AA 以内（H5）
--
-- 症状：元素/化合物卡上的符号文字，颜色是前端按底色现挑的（js/panels.js pickTextColor）。
--   原来那份挑法用的是 YIQ 亮度阈值 150，规则本身就不对——AA 要的是"对比度比值"，
--   YIQ 比的是"感知亮度"，两者在中段灰会交叉。全量重算 214 个底色后：
--     · 15 个符号在旧挑法下低于 4.5:1；
--     · 其中 11 个只是挑错了字色（底色本身两头都能达标），把挑法换成 WCAG 口径就救回来了；
--     · 剩下 4 个（硅 Si、铀 U、五水硫酸铜 CuSO4.5H2O、氢氧化铜 Cu(OH)2）确实是底色本身的问题：
--       它们正好落在死区里。配白字要求底色相对亮度 L ≤ 0.18333，
--       配墨字（#0f1b26，L=0.010249）要求 L ≥ 0.22112，中间这一小段两头都不达标。
--   最好的那一档也只有 4.23:1（胆矾/氢氧化铜的 #2e7dd1），玩家看到的就是糊的蓝底蓝字。
--   低视力玩家不会专门来反馈"这块看不清"，他只是看不清。
--
-- 修法：这四种各只往一个通道挪了 2~5（#6f7a82→#6b757d、#6e7d6a→#697765、
--   #2e7dd1→#2b75c3），肉眼几乎分辨不出来，但 4.5:1 的线过去了。
--   挪完之后 214 个底色全数达标，最紧的一对是 4.50:1。
--   挑色的口径由 test/a11y.js 的 palette 段钉住：谁再改出一个死区色，validate 当场红。
--
-- 为什么要有这一版：内容包的底本在库里。后台一改物质色，下发就以库为准覆盖前端默认值，
--   所以只改 js/data 等于"只修好了没联网的那一侧"。这一版把同一批色值同步进 content_item。
--
-- WHERE 里逐条带旧值比对：谁已经在后台把某个物质的颜色改成别的值，那是运营有意的选择，
--   迁移不把它按回种子色（和 V12 用 JSON_CONTAINS_PATH 守一手是同一个道理）。
--   updated_by 用 'v13' 而不是 'seed'：后台列表上一眼看出这一行是哪个版本带进来的。

UPDATE content_item SET data = JSON_SET(data, '$.color',
        CASE item_id
            WHEN 'Si'         THEN '#6b757d'
            WHEN 'U'          THEN '#697765'
            WHEN 'CuSO4.5H2O' THEN '#2b75c3'
            WHEN 'Cu(OH)2'    THEN '#2b75c3'
        END),
    updated_by = 'v13'
WHERE (content_type = 'element'  AND item_id = 'Si'
           AND JSON_UNQUOTE(JSON_EXTRACT(data, '$.color')) = '#6f7a82')
   OR (content_type = 'element'  AND item_id = 'U'
           AND JSON_UNQUOTE(JSON_EXTRACT(data, '$.color')) = '#6e7d6a')
   OR (content_type = 'compound' AND item_id = 'CuSO4.5H2O'
           AND JSON_UNQUOTE(JSON_EXTRACT(data, '$.color')) = '#2e7dd1')
   OR (content_type = 'compound' AND item_id = 'Cu(OH)2'
           AND JSON_UNQUOTE(JSON_EXTRACT(data, '$.color')) = '#2e7dd1');

-- ---------- 内容版本 +1：颜色跟着 bundle 下发，玩家侧要能看到 ----------
-- 同 V12:61。不加这一条，已缓存过内容包的客户端会继续用旧 bundle，改色等于没改。
UPDATE content_version SET version = version + 1 WHERE id = 1;
