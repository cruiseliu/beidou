-- cash id（petid/ringid 共用号段）改为 unique_id 表原子分配：
-- UPDATE ... RETURNING 递增取号，废除进程内计数器 + 存量集合方案（CashIdGenerator）。
CREATE TABLE IF NOT EXISTS `unique_id`
(
    `name`  TEXT PRIMARY KEY,
    `value` INTEGER NOT NULL
);

-- 号段种子：现有 pets_json.petid 与 rings.id 的最大值（petid/ringid 共用一个计数器）
INSERT INTO `unique_id` (`name`, `value`)
SELECT 'cash_id', COALESCE(MAX(x), 0)
FROM (SELECT MAX(`petid`) AS x FROM `pets_json`
      UNION ALL
      SELECT MAX(`id`) AS x FROM `rings`);
