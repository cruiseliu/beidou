-- nxcode_items：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `nxcode_items`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `codeid`   INT(11) NOT NULL,
    `type`     INT(11) NOT NULL DEFAULT '5',
    `item`     INT(11) NOT NULL DEFAULT '4000000',
    `quantity` INT(11) NOT NULL DEFAULT '1'

);
