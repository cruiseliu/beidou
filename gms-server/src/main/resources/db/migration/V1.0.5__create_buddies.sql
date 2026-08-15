CREATE TABLE IF NOT EXISTS `buddies`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11)    NOT NULL,
    `buddyid`     INT(11)    NOT NULL,
    `pending`     TINYINT(4) NOT NULL DEFAULT '0',
    `group`       VARCHAR(17)         DEFAULT '0'

);
