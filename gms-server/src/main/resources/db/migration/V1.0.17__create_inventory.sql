CREATE TABLE IF NOT EXISTS `inventoryequipment`
(
    `inventoryequipmentid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `inventoryitemid`      INT(10) NOT NULL DEFAULT '0',
    `upgradeslots`         INT(11)          NOT NULL DEFAULT '0',
    `level`                INT(11)          NOT NULL DEFAULT '0',
    `str`                  INT(11)          NOT NULL DEFAULT '0',
    `dex`                  INT(11)          NOT NULL DEFAULT '0',
    `int`                  INT(11)          NOT NULL DEFAULT '0',
    `luk`                  INT(11)          NOT NULL DEFAULT '0',
    `hp`                   INT(11)          NOT NULL DEFAULT '0',
    `mp`                   INT(11)          NOT NULL DEFAULT '0',
    `watk`                 INT(11)          NOT NULL DEFAULT '0',
    `matk`                 INT(11)          NOT NULL DEFAULT '0',
    `wdef`                 INT(11)          NOT NULL DEFAULT '0',
    `mdef`                 INT(11)          NOT NULL DEFAULT '0',
    `acc`                  INT(11)          NOT NULL DEFAULT '0',
    `avoid`                INT(11)          NOT NULL DEFAULT '0',
    `hands`                INT(11)          NOT NULL DEFAULT '0',
    `speed`                INT(11)          NOT NULL DEFAULT '0',
    `jump`                 INT(11)          NOT NULL DEFAULT '0',
    `locked`               INT(11)          NOT NULL DEFAULT '0',
    `vicious`              INT(11) NOT NULL DEFAULT '0',
    `itemlevel`            INT(11)          NOT NULL DEFAULT '1',
    `itemexp`              INT(11) NOT NULL DEFAULT '0',
    `ringid`               INT(11)          NOT NULL DEFAULT '-1'

);
CREATE INDEX IF NOT EXISTS `inventoryequipment_INVENTORYITEMID` ON `inventoryequipment` (`inventoryitemid`);



CREATE TABLE IF NOT EXISTS `inventoryitems`
(
    `inventoryitemid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `type`            TINYINT(3) NOT NULL,
    `characterid`     INT(11)                      DEFAULT NULL,
    `accountid`       INT(11)                      DEFAULT NULL,
    `itemid`          INT(11)             NOT NULL DEFAULT '0',
    `inventorytype`   INT(11)             NOT NULL DEFAULT '0',
    `position`        INT(11)             NOT NULL DEFAULT '0',
    `quantity`        INT(11)             NOT NULL DEFAULT '0',
    `owner`           TINYTEXT            NOT NULL,
    `petid`           INT(11)             NOT NULL DEFAULT '-1',
    `flag`            INT(11)             NOT NULL,
    `expiration`      BIGINT(20)          NOT NULL DEFAULT '-1',
    `giftFrom`        VARCHAR(26)         NOT NULL

);
CREATE INDEX IF NOT EXISTS `inventoryitems_CHARID` ON `inventoryitems` (`characterid`);



CREATE TABLE IF NOT EXISTS `inventorymerchant`
(
    `inventorymerchantid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `inventoryitemid`     INT(10) NOT NULL DEFAULT '0',
    `characterid`         INT(11)                   DEFAULT NULL,
    `bundles`             INT(10)          NOT NULL DEFAULT '0'

);
CREATE INDEX IF NOT EXISTS `inventorymerchant_INVENTORYITEMID` ON `inventorymerchant` (`inventoryitemid`);
