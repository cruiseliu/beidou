CREATE TABLE IF NOT EXISTS `pets`
(
    `petid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `name`      VARCHAR(13)               DEFAULT NULL,
    `level`     INT(10) NOT NULL,
    `closeness` INT(10) NOT NULL,
    `fullness`  INT(10) NOT NULL,
    `summoned`  TINYINT(1)       NOT NULL DEFAULT '0',
    `flag`      INT(10) NOT NULL DEFAULT '0'

);



CREATE TABLE IF NOT EXISTS `petignores`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `petid`  INT(11) NOT NULL,
    `itemid` INT(10) NOT NULL,
    CONSTRAINT `fk_petignorepetid` FOREIGN KEY (`petid`) REFERENCES `pets` (`petid`) ON DELETE CASCADE -- &

);
