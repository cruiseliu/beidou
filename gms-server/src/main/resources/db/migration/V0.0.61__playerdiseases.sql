-- playerdiseases：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `playerdiseases`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid`     INT(11) NOT NULL,
    `disease`    INT(11) NOT NULL,
    `mobskillid` INT(11) NOT NULL,
    `mobskilllv` INT(11) NOT NULL,
    `length`     INT(11) NOT NULL DEFAULT '1'

);
