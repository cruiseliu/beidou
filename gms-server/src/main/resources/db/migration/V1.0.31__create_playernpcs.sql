CREATE TABLE IF NOT EXISTS `playernpcs`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `name`         VARCHAR(13)      NOT NULL,
    `hair`         INT(11)          NOT NULL,
    `face`         INT(11)          NOT NULL,
    `skin`         INT(11)          NOT NULL,
    `gender`       INT(11)          NOT NULL DEFAULT '0',
    `x`            INT(11)          NOT NULL,
    `cy`           INT(11)          NOT NULL DEFAULT '0',
    `world`        INT(11)          NOT NULL DEFAULT '0',
    `map`          INT(11)          NOT NULL DEFAULT '0',
    `dir`          INT(11)          NOT NULL DEFAULT '0',
    `scriptid`     INT(10) NOT NULL DEFAULT '0',
    `fh`           INT(11)          NOT NULL DEFAULT '0',
    `rx0`          INT(11)          NOT NULL DEFAULT '0',
    `rx1`          INT(11)          NOT NULL DEFAULT '0',
    `worldrank`    INT(11)          NOT NULL DEFAULT '0',
    `overallrank`  INT(11)          NOT NULL DEFAULT '0',
    `worldjobrank` INT(11)          NOT NULL DEFAULT '0',
    `job`          INT(11)          NOT NULL DEFAULT '0'

);



CREATE TABLE IF NOT EXISTS `playernpcs_equip`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `npcid`    INT(11) NOT NULL DEFAULT '0',
    `equipid`  INT(11) NOT NULL,
    `type`     INT(11) NOT NULL DEFAULT '0',
    `equippos` INT(11) NOT NULL

);



CREATE TABLE IF NOT EXISTS `playernpcs_field`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `world`  INT(11)     NOT NULL,
    `map`    INT(11)     NOT NULL,
    `step`   TINYINT(1)  NOT NULL DEFAULT '0',
    `podium` SMALLINT(8) NOT NULL DEFAULT '0'

);
