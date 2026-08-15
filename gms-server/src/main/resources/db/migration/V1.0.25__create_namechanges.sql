CREATE TABLE IF NOT EXISTS `namechanges`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`    INT(11)     NOT NULL,
    `old`            VARCHAR(13) NOT NULL,
    `new`            VARCHAR(13) NOT NULL,
    `requestTime`    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completionTime` TIMESTAMP   NULL

);
CREATE INDEX IF NOT EXISTS `namechanges_idx1` ON `namechanges` (characterid);
