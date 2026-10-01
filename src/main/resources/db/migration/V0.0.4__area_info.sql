-- area_info：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `area_info`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid` INT(11)      NOT NULL,
    `area`   INT(11)      NOT NULL,
    `info`   VARCHAR(200) NOT NULL

);
