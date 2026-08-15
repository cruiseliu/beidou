CREATE TABLE IF NOT EXISTS `reactordrops`
(
    `reactordropid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `reactorid`     INT(11)          NOT NULL,
    `itemid`        INT(11)          NOT NULL,
    `chance`        INT(11)          NOT NULL,
    `questid`       INT(5)           NOT NULL DEFAULT '-1'

);
CREATE INDEX IF NOT EXISTS `reactordrops_reactorid` ON `reactordrops` (`reactorid`);
