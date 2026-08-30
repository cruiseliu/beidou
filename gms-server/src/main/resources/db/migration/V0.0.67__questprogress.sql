-- questprogress：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `questprogress`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`   INT(11)                                                      NOT NULL,
    `queststatusid` INT(10)                                             NOT NULL DEFAULT '0',
    `progressid`    INT(11)                                                      NOT NULL DEFAULT '0',
    `progress`      VARCHAR(15) NOT NULL DEFAULT ''

);
