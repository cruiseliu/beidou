-- inventoryitems：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `inventoryitems`
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
CREATE INDEX `inventoryitems_CHARID` ON `inventoryitems` (`characterid`);
CREATE INDEX `inventoryitems_idx_accountid` ON `inventoryitems` (`accountid`);
INSERT INTO `inventoryitems` (`inventoryitemid`, `type`, `characterid`, `accountid`, `itemid`, `inventorytype`, `position`, `quantity`, `owner`, `petid`, `flag`, `expiration`, `giftFrom`) VALUES
(21, 1, 1, NULL, 4161001, 4, 1, 1, '', -1, 0, -1, ''),
(22, 1, 1, NULL, 1040002, -1, -5, 1, '', -1, 0, -1, ''),
(23, 1, 1, NULL, 1060002, -1, -6, 1, '', -1, 0, -1, ''),
(24, 1, 1, NULL, 1072001, -1, -7, 1, '', -1, 0, -1, ''),
(25, 1, 1, NULL, 1302000, -1, -11, 1, '', -1, 0, -1, '');
