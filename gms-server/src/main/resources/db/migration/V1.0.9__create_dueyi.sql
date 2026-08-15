CREATE TABLE IF NOT EXISTS `dueyitems`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `PackageId`       INT(10) NOT NULL DEFAULT '0',
    `inventoryitemid` INT(10) NOT NULL DEFAULT '0'

);
CREATE INDEX IF NOT EXISTS `dueyitems_INVENTORYITEMID` ON `dueyitems` (`inventoryitemid`);
CREATE INDEX IF NOT EXISTS `dueyitems_PackageId` ON `dueyitems` (`PackageId`);



CREATE TABLE IF NOT EXISTS `dueypackages`
(
    `PackageId` INTEGER PRIMARY KEY AUTOINCREMENT,
    `ReceiverId` INT(10) NOT NULL,
    `SenderName` VARCHAR(13)      NOT NULL,
    `Mesos`      INT(10)          DEFAULT '0',
    `TIMESTAMP`  TIMESTAMP        NOT NULL DEFAULT '2015-01-01 05:00:00',
    `Message`    VARCHAR(200)     NULL,
    `Checked`    TINYINT(1)       DEFAULT '1',
    `Type`       TINYINT(1)       DEFAULT '0'

);
