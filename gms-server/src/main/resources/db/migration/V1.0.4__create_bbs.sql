CREATE TABLE IF NOT EXISTS `bbs_replies`
(
    `replyid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `threadid`  INT(10)    NOT NULL,
    `postercid` INT(10)    NOT NULL,
    `TIMESTAMP` BIGINT(20) NOT NULL,
    `content`   VARCHAR(26)         NOT NULL DEFAULT ''

);



CREATE TABLE IF NOT EXISTS `bbs_threads`
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
