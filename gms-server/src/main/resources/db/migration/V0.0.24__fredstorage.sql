-- fredstorage：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `fredstorage`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `cid`       INT(10) NOT NULL,
    `daynotes`  INT(4)  NOT NULL,
    `TIMESTAMP` TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP

);
CREATE UNIQUE INDEX `fredstorage_cid_2` ON `fredstorage` (`cid`);
