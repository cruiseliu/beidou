-- playernpcs_equip：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `playernpcs_equip`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `npcid`    INT(11) NOT NULL DEFAULT '0',
    `equipid`  INT(11) NOT NULL,
    `type`     INT(11) NOT NULL DEFAULT '0',
    `equippos` INT(11) NOT NULL

);
