-- inventorymerchant：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `inventorymerchant`
(
    `inventorymerchantid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `inventoryitemid`     INT(10) NOT NULL DEFAULT '0',
    `characterid`         INT(11)                   DEFAULT NULL,
    `bundles`             INT(10)          NOT NULL DEFAULT '0'

);
CREATE INDEX `inventorymerchant_INVENTORYITEMID` ON `inventorymerchant` (`inventoryitemid`);
