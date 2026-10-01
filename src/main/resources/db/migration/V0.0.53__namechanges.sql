-- namechanges：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `namechanges`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`    INT(11)     NOT NULL,
    `old`            VARCHAR(13) NOT NULL,
    `new`            VARCHAR(13) NOT NULL,
    `requestTime`    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completionTime` TIMESTAMP   NULL

);
CREATE INDEX `namechanges_idx1` ON `namechanges` (characterid);
