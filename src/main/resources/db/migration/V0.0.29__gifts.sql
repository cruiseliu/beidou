-- gifts：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `gifts`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `to`      INT(11)          NOT NULL,
    `from`    VARCHAR(13)      NOT NULL,
    `message` TINYTEXT         NOT NULL,
    `sn`      INT(10) NOT NULL,
    `ringid`  INT(10)          NOT NULL

);
