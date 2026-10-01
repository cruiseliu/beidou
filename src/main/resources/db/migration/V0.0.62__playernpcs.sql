-- playernpcs：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `playernpcs`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `name`         VARCHAR(13)      NOT NULL,
    `hair`         INT(11)          NOT NULL,
    `face`         INT(11)          NOT NULL,
    `skin`         INT(11)          NOT NULL,
    `gender`       INT(11)          NOT NULL DEFAULT '0',
    `x`            INT(11)          NOT NULL,
    `cy`           INT(11)          NOT NULL DEFAULT '0',
    `world`        INT(11)          NOT NULL DEFAULT '0',
    `map`          INT(11)          NOT NULL DEFAULT '0',
    `dir`          INT(11)          NOT NULL DEFAULT '0',
    `scriptid`     INT(10) NOT NULL DEFAULT '0',
    `fh`           INT(11)          NOT NULL DEFAULT '0',
    `rx0`          INT(11)          NOT NULL DEFAULT '0',
    `rx1`          INT(11)          NOT NULL DEFAULT '0',
    `worldrank`    INT(11)          NOT NULL DEFAULT '0',
    `overallrank`  INT(11)          NOT NULL DEFAULT '0',
    `worldjobrank` INT(11)          NOT NULL DEFAULT '0',
    `job`          INT(11)          NOT NULL DEFAULT '0'

);
