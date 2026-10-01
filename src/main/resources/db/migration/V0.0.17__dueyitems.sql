-- dueyitems：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `dueyitems`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `PackageId`       INT(10) NOT NULL DEFAULT '0',
    `inventoryitemid` INT(10) NOT NULL DEFAULT '0'

);
CREATE INDEX `dueyitems_INVENTORYITEMID` ON `dueyitems` (`inventoryitemid`);
CREATE INDEX `dueyitems_PackageId` ON `dueyitems` (`PackageId`);
