CREATE TABLE IF NOT EXISTS `cooldowns`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid`    INT(11)             NOT NULL,
    `SkillID`   INT(11)             NOT NULL,
    `length`    BIGINT(20) NOT NULL,
    `StartTime` BIGINT(20) NOT NULL

);
