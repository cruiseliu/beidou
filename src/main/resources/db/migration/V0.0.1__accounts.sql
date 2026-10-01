-- accounts：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `accounts`
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
CREATE INDEX `accounts_idx3` ON `accounts` (id, name);
CREATE INDEX `accounts_idx4` ON `accounts` (id, nxCredit, maplePoint, nxPrepaid);
CREATE UNIQUE INDEX `accounts_name` ON `accounts` (`name`);
CREATE INDEX `accounts_ranking1` ON `accounts` (`id`, `banned`);
INSERT INTO `accounts` (`id`, `name`, `password`, `pin`, `pic`, `loggedin`, `lastlogin`, `createdat`, `birthday`, `banned`, `banreason`, `macs`, `nxCredit`, `maplePoint`, `nxPrepaid`, `characterslots`, `gender`, `tempban`, `greason`, `tos`, `sitelogged`, `webadmin`, `nick`, `mute`, `email`, `ip`, `rewardpoints`, `votepoints`, `hwid`, `language`) VALUES
(1, 'admin', '$2y$12$aFD9BDeUocDMY1X4tDYDyeJw/HhkQwCQWs3KAY7gCaRG0cpqJcaL.', '0000', '000000', 0, '2021-05-24 00:00:01', '2021-05-24 00:00:02', '2005-05-11', 0, NULL, NULL, 1000000, 1000000, 1000000, 3, 0, '2005-05-11 03:00:00', 0, 1, NULL, 1, NULL, 0, NULL, NULL, 0, 0, '1234-5678', 3);
