-- guilds：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `guilds`
(
    `guildid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `leader`      INT(10)     NOT NULL DEFAULT '0',
    `GP`          INT(10)     NOT NULL DEFAULT '0',
    `logo`        INT(10)              DEFAULT NULL,
    `logoColor`   SMALLINT(5) NOT NULL DEFAULT '0',
    `name`        VARCHAR(45)          NOT NULL,
    `rank1title`  VARCHAR(45)          NOT NULL DEFAULT 'Master',
    `rank2title`  VARCHAR(45)          NOT NULL DEFAULT 'Jr. Master',
    `rank3title`  VARCHAR(45)          NOT NULL DEFAULT 'Member',
    `rank4title`  VARCHAR(45)          NOT NULL DEFAULT 'Member',
    `rank5title`  VARCHAR(45)          NOT NULL DEFAULT 'Member',
    `capacity`    INT(10)     NOT NULL DEFAULT '10',
    `logoBG`      INT(10)              DEFAULT NULL,
    `logoBGColor` SMALLINT(5) NOT NULL DEFAULT '0',
    `notice`      VARCHAR(101)                  DEFAULT NULL,
    `signature`   INT(11)              NOT NULL DEFAULT '0',
    `allianceId`  INT(11)     NOT NULL DEFAULT '0'

);
CREATE INDEX `guilds_idx1` ON `guilds` (guildid, name);
