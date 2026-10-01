-- pets：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `pets`
(
    `petid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `name`      VARCHAR(13)               DEFAULT NULL,
    `level`     INT(10) NOT NULL,
    `closeness` INT(10) NOT NULL,
    `fullness`  INT(10) NOT NULL,
    `summoned`  TINYINT(1)       NOT NULL DEFAULT '0',
    `flag`      INT(10) NOT NULL DEFAULT '0'

);
