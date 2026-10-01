-- worldtransfers：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `worldtransfers`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`    INT(11)    NOT NULL,
    `from`           TINYINT(3) NOT NULL,
    `to`             TINYINT(3) NOT NULL,
    `requestTime`    TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completionTime` TIMESTAMP  NULL

);
CREATE INDEX `worldtransfers_idx1` ON `worldtransfers` (characterid);
