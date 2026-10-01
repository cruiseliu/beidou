-- storages：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `storages`
(
    `storageid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `accountid` INT(11)          NOT NULL DEFAULT '0',
    `world`     INT(2)           NOT NULL,
    `slots`     INT(11)          NOT NULL DEFAULT '0',
    `meso`      INT(11)          NOT NULL DEFAULT '0'

);
INSERT INTO `storages` (`storageid`, `accountid`, `world`, `slots`, `meso`) VALUES
(1, 1, 0, 4, 0);
