-- rings：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `rings`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `partnerRingId` INT(11)      NOT NULL DEFAULT '0',
    `partnerChrId`  INT(11)      NOT NULL DEFAULT '0',
    `itemid`        INT(11)      NOT NULL DEFAULT '0',
    `partnername`   VARCHAR(255) NOT NULL

);
