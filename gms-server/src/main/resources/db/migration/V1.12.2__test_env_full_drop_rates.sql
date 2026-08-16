-- 测试环境固化：测试用掉率改为必掉（chance 单位为百万分比，1000000 = 100%）。
-- 蘑菇仔(120100) 蘑菇芽孢 4000011、蓝蜗牛(100101) 蜗牛壳 4000000：任务/拾取场景需要稳定产出；
-- 蓝蜗牛金币同步提高，方便掉落测试观察。
UPDATE `drop_data` SET `chance` = 1000000 WHERE `dropperid` = 120100 AND `itemid` = 4000011;
UPDATE `drop_data` SET `chance` = 1000000 WHERE `dropperid` = 100101 AND `itemid` = 4000000;
UPDATE `drop_data` SET `chance` = 1000000 WHERE `dropperid` = 100101 AND `itemid` = 0;
