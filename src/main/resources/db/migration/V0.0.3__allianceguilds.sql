-- allianceguilds：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `allianceguilds`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `allianceid` INT(10)          NOT NULL DEFAULT '-1',
    `guildid`    INT(10)          NOT NULL DEFAULT '-1'

);
