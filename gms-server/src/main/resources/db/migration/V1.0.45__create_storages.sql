CREATE TABLE IF NOT EXISTS `storages`
(
    `storageid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `accountid` INT(11)          NOT NULL DEFAULT '0',
    `world`     INT(2)           NOT NULL,
    `slots`     INT(11)          NOT NULL DEFAULT '0',
    `meso`      INT(11)          NOT NULL DEFAULT '0'

);
