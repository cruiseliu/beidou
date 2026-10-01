-- medalmaps：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `medalmaps`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`   INT(11)          NOT NULL,
    `queststatusid` INT(11) NOT NULL,
    `mapid`         INT(11)          NOT NULL

);
CREATE INDEX `medalmaps_queststatusid` ON `medalmaps` (`queststatusid`);
