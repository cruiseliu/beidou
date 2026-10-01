-- bbs_threads：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `bbs_threads`
(
    `threadid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `postercid`     INT(10)     NOT NULL,
    `name`          VARCHAR(26)          NOT NULL DEFAULT '',
    `TIMESTAMP`     BIGINT(20)  NOT NULL,
    `icon`          SMALLINT(5) NOT NULL,
    `replycount`    SMALLINT(5) NOT NULL DEFAULT '0',
    `startpost`     TEXT                 NOT NULL,
    `guildid`       INT(10)     NOT NULL,
    `localthreadid` INT(10)     NOT NULL

);
