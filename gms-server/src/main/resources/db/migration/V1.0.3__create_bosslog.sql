CREATE TABLE IF NOT EXISTS `bosslog_daily`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11)                                                   NOT NULL,
    `bosstype`    TEXT NOT NULL,
    `attempttime` TIMESTAMP                                                 NOT NULL DEFAULT CURRENT_TIMESTAMP

);



CREATE TABLE IF NOT EXISTS `bosslog_weekly`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11)                                                   NOT NULL,
    `bosstype`    TEXT NOT NULL,
    `attempttime` TIMESTAMP                                                 NOT NULL DEFAULT CURRENT_TIMESTAMP

);
