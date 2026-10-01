-- petignores：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `petignores`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `petid`  INT(11) NOT NULL,
    `itemid` INT(10) NOT NULL,
    CONSTRAINT `fk_petignorepetid` FOREIGN KEY (`petid`) REFERENCES `pets` (`petid`) ON DELETE CASCADE -- &

);
