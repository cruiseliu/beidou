CREATE TABLE IF NOT EXISTS `drop_data`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `dropperid`        INT(11)    NOT NULL,
    `itemid`           INT(11)    NOT NULL DEFAULT '0',
    `minimum_quantity` INT(11)    NOT NULL DEFAULT '1',
    `maximum_quantity` INT(11)    NOT NULL DEFAULT '1',
    `questid`          INT(11)    NOT NULL DEFAULT '0',
    `chance`           INT(11)    NOT NULL DEFAULT '0'

);
CREATE UNIQUE INDEX IF NOT EXISTS `drop_data_uq1` ON `drop_data` (`dropperid`, `itemid`);
CREATE INDEX IF NOT EXISTS `drop_data_mobid` ON `drop_data` (`dropperid`);
CREATE INDEX IF NOT EXISTS `drop_data_idx3` ON `drop_data` (dropperid, itemid);



CREATE TABLE IF NOT EXISTS `drop_data_global`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `continent`        TINYINT(1) NOT NULL DEFAULT '-1',
    `itemid`           INT(11)    NOT NULL DEFAULT '0',
    `minimum_quantity` INT(11)    NOT NULL DEFAULT '1',
    `maximum_quantity` INT(11)    NOT NULL DEFAULT '1',
    `questid`          INT(11)    NOT NULL DEFAULT '0',
    `chance`           INT(11)    NOT NULL DEFAULT '0',
    `comments`         VARCHAR(45)         DEFAULT NULL

);
CREATE INDEX IF NOT EXISTS `drop_data_global_mobid` ON `drop_data_global` (`continent`);
