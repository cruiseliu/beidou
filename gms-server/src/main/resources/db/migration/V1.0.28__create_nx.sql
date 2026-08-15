CREATE TABLE IF NOT EXISTS `nxcode`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `code`       VARCHAR(17)         NOT NULL UNIQUE,
    `retriever`  VARCHAR(13)                  DEFAULT NULL,
    `expiration` BIGINT(20) NOT NULL DEFAULT '0'

);



CREATE TABLE IF NOT EXISTS `nxcode_items`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `codeid`   INT(11) NOT NULL,
    `type`     INT(11) NOT NULL DEFAULT '5',
    `item`     INT(11) NOT NULL DEFAULT '4000000',
    `quantity` INT(11) NOT NULL DEFAULT '1'

);



CREATE TABLE IF NOT EXISTS `nxcoupons`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `couponid`  INT(11) NOT NULL DEFAULT '0',
    `rate`      INT(11) NOT NULL DEFAULT '0',
    `activeday` INT(11) NOT NULL DEFAULT '0',
    `starthour` INT(11) NOT NULL DEFAULT '0',
    `endhour`   INT(11) NOT NULL DEFAULT '0'

);
