-- skillmacros：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `skillmacros`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11)    NOT NULL DEFAULT '0',
    `position`    TINYINT(1) NOT NULL DEFAULT '0',
    `skill1`      INT(11)    NOT NULL DEFAULT '0',
    `skill2`      INT(11)    NOT NULL DEFAULT '0',
    `skill3`      INT(11)    NOT NULL DEFAULT '0',
    `name`        VARCHAR(13)         DEFAULT NULL,
    `shout`       TINYINT(1) NOT NULL DEFAULT '0'

);
