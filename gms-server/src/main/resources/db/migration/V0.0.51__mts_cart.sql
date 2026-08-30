-- mts_cart：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `mts_cart`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `cid`    INT(11) NOT NULL,
    `itemid` INT(11) NOT NULL

);
