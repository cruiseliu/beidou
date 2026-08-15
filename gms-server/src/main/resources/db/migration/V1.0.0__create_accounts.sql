CREATE TABLE IF NOT EXISTS `accounts`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `name`           VARCHAR(13)  NOT NULL DEFAULT '',
    `password`       VARCHAR(128) NOT NULL DEFAULT '',
    `pin`            VARCHAR(10)  NOT NULL DEFAULT '',
    `pic`            VARCHAR(26)  NOT NULL DEFAULT '',
    `loggedin`       TINYINT(4)   NOT NULL DEFAULT '0',
    `lastlogin`      TIMESTAMP    NULL     DEFAULT NULL,
    `createdat`      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `birthday`       DATE         NOT NULL DEFAULT '2005-05-11',
    `banned`         TINYINT(1)   NOT NULL DEFAULT '0',
    `banreason`      TEXT,
    `macs`           TINYTEXT,
    `nxCredit`       INT(11)               DEFAULT NULL,
    `maplePoint`     INT(11)               DEFAULT NULL,
    `nxPrepaid`      INT(11)               DEFAULT NULL,
    `characterslots` TINYINT(2)   NOT NULL DEFAULT '3',
    `gender`         TINYINT(2)   NOT NULL DEFAULT '10',
    `tempban`        TIMESTAMP    NOT NULL DEFAULT '2005-05-11 00:00:00',
    `greason`        TINYINT(4)   NOT NULL DEFAULT '0',
    `tos`            TINYINT(1)   NOT NULL DEFAULT '0',
    `sitelogged`     TEXT,
    `webadmin`       INT(1)                DEFAULT '0',
    `nick`           VARCHAR(20)           DEFAULT NULL,
    `mute`           INT(1)                DEFAULT '0',
    `email`          VARCHAR(45)           DEFAULT NULL,
    `ip`             TEXT,
    `rewardpoints`   INT(11)      NOT NULL DEFAULT '0',
    `votepoints`     INT(11)      NOT NULL DEFAULT '0',
    `hwid`           VARCHAR(12)  NOT NULL DEFAULT '',
    `language`       INT(1)       NOT NULL DEFAULT '3'

);
CREATE UNIQUE INDEX IF NOT EXISTS `accounts_name` ON `accounts` (`name`);
CREATE INDEX IF NOT EXISTS `accounts_ranking1` ON `accounts` (`id`, `banned`);
CREATE INDEX IF NOT EXISTS `accounts_idx3` ON `accounts` (id, name);
CREATE INDEX IF NOT EXISTS `accounts_idx4` ON `accounts` (id, nxCredit, maplePoint, nxPrepaid);
