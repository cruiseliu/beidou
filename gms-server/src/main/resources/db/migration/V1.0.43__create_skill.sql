CREATE TABLE IF NOT EXISTS `skillmacros`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11)    NOT NULL DEFAULT '0',
    `position`    TINYINT(1) NOT NULL DEFAULT '0',
    `skill1`      INT(11)    NOT NULL DEFAULT '0',
    `skill2`      INT(11)    NOT NULL DEFAULT '0',
    `skill3`      INT(11)    NOT NULL DEFAULT '0',
    `name`        VARCHAR(13)         DEFAULT NULL,
    `shout`       TINYINT(1) NOT NULL DEFAULT '0'

);



CREATE TABLE IF NOT EXISTS `skills`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `skillid`     INT(11)    NOT NULL DEFAULT '0',
    `characterid` INT(11)    NOT NULL DEFAULT '0',
    `skilllevel`  INT(11)    NOT NULL DEFAULT '0',
    `masterlevel` INT(11)    NOT NULL DEFAULT '0',
    `expiration`  BIGINT(20) NOT NULL DEFAULT '-1'

);
CREATE UNIQUE INDEX IF NOT EXISTS `skills_skillpair` ON `skills` (`skillid`, `characterid`);
