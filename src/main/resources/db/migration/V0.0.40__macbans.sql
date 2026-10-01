-- macbans：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `macbans`
(
    `macbanid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `mac`      VARCHAR(30)      NOT NULL,
    `aid`      VARCHAR(40) DEFAULT NULL

);
CREATE UNIQUE INDEX `macbans_mac_2` ON `macbans` (`mac`);
