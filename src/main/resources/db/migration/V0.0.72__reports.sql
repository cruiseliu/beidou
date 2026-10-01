-- reports：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `reports`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `reporttime`  TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `reporterid`  INT(11)          NOT NULL,
    `victimid`    INT(11)          NOT NULL,
    `reason`      TINYINT(4)       NOT NULL,
    `chatlog`     TEXT             NOT NULL,
    `description` TEXT             NOT NULL

);
