-- hp_mp_alert：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `hp_mp_alert`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `c_id` INT(11) NOT NULL,
    `hp`   TINYINT NOT NULL DEFAULT 10,
    `mp`   TINYINT NOT NULL DEFAULT 10

);
CREATE UNIQUE INDEX `hp_mp_alert_uq1` ON `hp_mp_alert` (`c_id`);
