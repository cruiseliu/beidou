CREATE TABLE IF NOT EXISTS `monsterbook`
(
    `charid` INT(11) NOT NULL,
    `cardid` INT(11) NOT NULL,
    `level`  INT(1)  NOT NULL DEFAULT '1',
    PRIMARY KEY (`charid`, `cardid`),
    CONSTRAINT `FK_monsterbook_characters` FOREIGN KEY (`charid`) REFERENCES `characters` (`id`) ON UPDATE CASCADE ON DELETE CASCADE

);



CREATE TABLE IF NOT EXISTS `monstercarddata`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `cardid` INT(11) NOT NULL DEFAULT '0',
    `mobid`  INT(11) NOT NULL DEFAULT '0'

);
CREATE UNIQUE INDEX IF NOT EXISTS `monstercarddata_id` ON `monstercarddata` (`id`);

