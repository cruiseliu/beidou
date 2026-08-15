CREATE TABLE IF NOT EXISTS `famelog`
(
    `famelogid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`    INT(11)   NOT NULL DEFAULT '0',
    `characterid_to` INT(11)   NOT NULL DEFAULT '0',
    `when`           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP

);
CREATE INDEX IF NOT EXISTS `famelog_characterid` ON `famelog` (`characterid`);
