CREATE TABLE IF NOT EXISTS `hwidaccounts`
(
    `accountid` INT(11)     NOT NULL DEFAULT '0',
    `hwid`      VARCHAR(40) NOT NULL DEFAULT '',
    `relevance` TINYINT(2)  NOT NULL DEFAULT '0',
    `expiresat` TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`accountid`, `hwid`)

);



CREATE TABLE IF NOT EXISTS `hwidbans`
(
    `hwidbanid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `hwid`      VARCHAR(30)      NOT NULL

);
CREATE UNIQUE INDEX IF NOT EXISTS `hwidbans_hwid_2` ON `hwidbans` (`hwid`);
