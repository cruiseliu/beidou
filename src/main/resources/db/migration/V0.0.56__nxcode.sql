-- nxcode：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `nxcode`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `code`       VARCHAR(17)         NOT NULL UNIQUE,
    `retriever`  VARCHAR(13)                  DEFAULT NULL,
    `expiration` BIGINT(20) NOT NULL DEFAULT '0'

);
