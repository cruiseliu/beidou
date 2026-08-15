CREATE TABLE IF NOT EXISTS `questactions`
(
    `questactionid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `questid`       INT(11)          NOT NULL DEFAULT '0',
    `status`        INT(11)          NOT NULL DEFAULT '0',
    `data`          BLOB             NOT NULL

);



CREATE TABLE IF NOT EXISTS `questprogress`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`   INT(11)                                                      NOT NULL,
    `queststatusid` INT(10)                                             NOT NULL DEFAULT '0',
    `progressid`    INT(11)                                                      NOT NULL DEFAULT '0',
    `progress`      VARCHAR(15) NOT NULL DEFAULT ''

);



CREATE TABLE IF NOT EXISTS `questrequirements`
(
    `questrequirementid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `questid`            INT(11)          NOT NULL DEFAULT '0',
    `status`             INT(11)          NOT NULL DEFAULT '0',
    `data`               BLOB             NOT NULL

);



CREATE TABLE IF NOT EXISTS `queststatus`
(
    `queststatusid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`   INT(11)          NOT NULL DEFAULT '0',
    `quest`         INT(11)          NOT NULL DEFAULT '0',
    `status`        INT(11)          NOT NULL DEFAULT '0',
    `time`          INT(11)          NOT NULL DEFAULT '0',
    `expires`       BIGINT(20)       NOT NULL DEFAULT '0',
    `forfeited`     INT(11)          NOT NULL DEFAULT '0',
    `completed`     INT(11)          NOT NULL DEFAULT '0',
    `info`          TINYINT(3)       NOT NULL DEFAULT '0'

);
