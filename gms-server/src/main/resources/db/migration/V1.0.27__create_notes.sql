CREATE TABLE IF NOT EXISTS `notes`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `to`        VARCHAR(13)         NOT NULL DEFAULT '',
    `from`      VARCHAR(13)         NOT NULL DEFAULT '',
    `message`   TEXT                NOT NULL,
    `TIMESTAMP` BIGINT(20) NOT NULL,
    `fame`      INT(11)             NOT NULL DEFAULT '0',
    `deleted`   INT(2)              NOT NULL DEFAULT '0'

);
