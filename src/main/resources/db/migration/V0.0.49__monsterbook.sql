-- monsterbook：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `monsterbook`
(
    `charid` INT(11) NOT NULL,
    `cardid` INT(11) NOT NULL,
    `level`  INT(1)  NOT NULL DEFAULT '1',
    PRIMARY KEY (`charid`, `cardid`),
    CONSTRAINT `FK_monsterbook_characters` FOREIGN KEY (`charid`) REFERENCES `characters` (`id`) ON UPDATE CASCADE ON DELETE CASCADE

);
