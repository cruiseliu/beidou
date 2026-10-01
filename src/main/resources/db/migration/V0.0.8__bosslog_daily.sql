-- bosslog_daily：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `bosslog_daily`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11)                                                   NOT NULL,
    `bosstype`    TEXT NOT NULL,
    `attempttime` TIMESTAMP                                                 NOT NULL DEFAULT CURRENT_TIMESTAMP

);
