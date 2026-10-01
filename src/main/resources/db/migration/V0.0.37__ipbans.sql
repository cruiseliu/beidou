-- ipbans：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `ipbans`
(
    `ipbanid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `ip`      VARCHAR(40)      NOT NULL DEFAULT '',
    `aid`     VARCHAR(40)               DEFAULT NULL

);
