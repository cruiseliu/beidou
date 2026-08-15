CREATE TABLE IF NOT EXISTS `server_queue`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `accountid`   INT(11)      NOT NULL DEFAULT '0',
    `characterid` INT(11)      NOT NULL DEFAULT '0',
    `type`        TINYINT(2)   NOT NULL DEFAULT '0',
    `value`       INT(10)      NOT NULL DEFAULT '0',
    `message`     VARCHAR(128) NOT NULL,
    `createTime`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP

);
