CREATE TABLE IF NOT EXISTS `medalmaps`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`   INT(11)          NOT NULL,
    `queststatusid` INT(11) NOT NULL,
    `mapid`         INT(11)          NOT NULL

);
CREATE INDEX IF NOT EXISTS `medalmaps_queststatusid` ON `medalmaps` (`queststatusid`);
