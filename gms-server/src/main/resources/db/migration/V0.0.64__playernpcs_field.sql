-- playernpcs_field：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `playernpcs_field`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `world`  INT(11)     NOT NULL,
    `map`    INT(11)     NOT NULL,
    `step`   TINYINT(1)  NOT NULL DEFAULT '0',
    `podium` SMALLINT(8) NOT NULL DEFAULT '0'

);
