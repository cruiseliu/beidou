-- newyear：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `newyear`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `senderid`        INT(10)             NOT NULL DEFAULT '-1',
    `sendername`      VARCHAR(13)                  DEFAULT '',
    `receiverid`      INT(10)             NOT NULL DEFAULT '-1',
    `receivername`    VARCHAR(13)                  DEFAULT '',
    `message`         VARCHAR(120)                 DEFAULT '',
    `senderdiscard`   TINYINT(1)          NOT NULL DEFAULT '0',
    `receiverdiscard` TINYINT(1)          NOT NULL DEFAULT '0',
    `received`        TINYINT(1)          NOT NULL DEFAULT '0',
    `timesent`        BIGINT(20) NOT NULL,
    `timereceived`    BIGINT(20) NOT NULL

);
