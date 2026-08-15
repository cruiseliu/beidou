CREATE TABLE IF NOT EXISTS `ipbans`
(
    `ipbanid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `ip`      VARCHAR(40)      NOT NULL DEFAULT '',
    `aid`     VARCHAR(40)               DEFAULT NULL

);



CREATE TABLE IF NOT EXISTS `macbans`
(
    `macbanid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `mac`      VARCHAR(30)      NOT NULL,
    `aid`      VARCHAR(40) DEFAULT NULL

);
CREATE UNIQUE INDEX IF NOT EXISTS `macbans_mac_2` ON `macbans` (`mac`);



CREATE TABLE IF NOT EXISTS `macfilters`
(
    `macfilterid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `filter`      VARCHAR(30)      NOT NULL

);
