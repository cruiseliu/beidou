CREATE TABLE IF NOT EXISTS `rings`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `partnerRingId` INT(11)      NOT NULL DEFAULT '0',
    `partnerChrId`  INT(11)      NOT NULL DEFAULT '0',
    `itemid`        INT(11)      NOT NULL DEFAULT '0',
    `partnername`   VARCHAR(255) NOT NULL

);
