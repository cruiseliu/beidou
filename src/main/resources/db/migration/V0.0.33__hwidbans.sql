-- hwidbans：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `hwidbans`
(
    `hwidbanid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `hwid`      VARCHAR(30)      NOT NULL

);
CREATE UNIQUE INDEX `hwidbans_hwid_2` ON `hwidbans` (`hwid`);
