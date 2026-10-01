-- autoban_config：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `autoban_config`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `type`        VARCHAR(32)  NOT NULL,
    `disabled`    TINYINT(1)   NOT NULL DEFAULT 0,
    `points`      INT(11)      NULL,
    `expire_time` BIGINT       NULL,
    `description` VARCHAR(128) NULL,
    `create_time` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` TIMESTAMP    NULL

);
CREATE UNIQUE INDEX `autoban_config_uk_type` ON `autoban_config` (`type`);
