CREATE TABLE IF NOT EXISTS `gifts`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `to`      INT(11)          NOT NULL,
    `from`    VARCHAR(13)      NOT NULL,
    `message` TINYTEXT         NOT NULL,
    `sn`      INT(10) NOT NULL,
    `ringid`  INT(10)          NOT NULL

);
