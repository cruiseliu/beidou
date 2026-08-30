-- famelog：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `famelog`
(
    `famelogid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`    INT(11)   NOT NULL DEFAULT '0',
    `characterid_to` INT(11)   NOT NULL DEFAULT '0',
    `when`           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP

);
CREATE INDEX `famelog_characterid` ON `famelog` (`characterid`);
