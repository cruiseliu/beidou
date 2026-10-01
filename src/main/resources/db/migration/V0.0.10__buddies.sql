-- buddies：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `buddies`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11)    NOT NULL,
    `buddyid`     INT(11)    NOT NULL,
    `pending`     TINYINT(4) NOT NULL DEFAULT '0',
    `group`       VARCHAR(17)         DEFAULT '0'

);
CREATE INDEX `buddies_idx_characterid` ON `buddies` (`characterid`);
