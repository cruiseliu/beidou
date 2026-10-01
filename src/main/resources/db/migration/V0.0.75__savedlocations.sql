-- savedlocations：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `savedlocations`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`  INT(11) NOT NULL,
    `locationtype` TEXT            NOT NULL,
    `map`          INT(11) NOT NULL,
    `portal`       INT(11) NOT NULL

);
