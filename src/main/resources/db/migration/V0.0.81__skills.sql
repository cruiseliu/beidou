-- skills：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `skills`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `skillid`     INT(11)    NOT NULL DEFAULT '0',
    `characterid` INT(11)    NOT NULL DEFAULT '0',
    `skilllevel`  INT(11)    NOT NULL DEFAULT '0',
    `masterlevel` INT(11)    NOT NULL DEFAULT '0',
    `expiration`  BIGINT(20) NOT NULL DEFAULT '-1'

);
CREATE UNIQUE INDEX `skills_skillpair` ON `skills` (`skillid`, `characterid`);
