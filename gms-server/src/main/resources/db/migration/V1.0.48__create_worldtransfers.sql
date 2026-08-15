CREATE TABLE IF NOT EXISTS `worldtransfers`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`    INT(11)    NOT NULL,
    `from`           TINYINT(3) NOT NULL,
    `to`             TINYINT(3) NOT NULL,
    `requestTime`    TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completionTime` TIMESTAMP  NULL

);
CREATE INDEX IF NOT EXISTS `worldtransfers_idx1` ON `worldtransfers` (characterid);
