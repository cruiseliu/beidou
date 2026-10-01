-- cooldowns：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `cooldowns`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid`    INT(11)             NOT NULL,
    `SkillID`   INT(11)             NOT NULL,
    `length`    BIGINT(20) NOT NULL,
    `StartTime` BIGINT(20) NOT NULL

);
