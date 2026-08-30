-- inventoryequipment：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `inventoryequipment`
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
CREATE INDEX `inventoryequipment_INVENTORYITEMID` ON `inventoryequipment` (`inventoryitemid`);
INSERT INTO `inventoryequipment` (`inventoryequipmentid`, `inventoryitemid`, `upgradeslots`, `level`, `str`, `dex`, `int`, `luk`, `hp`, `mp`, `watk`, `matk`, `wdef`, `mdef`, `acc`, `avoid`, `hands`, `speed`, `jump`, `locked`, `vicious`, `itemlevel`, `itemexp`, `ringid`) VALUES
(17, 22, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, -1),
(18, 23, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, -1),
(19, 24, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, -1),
(20, 25, 7, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, -1);
