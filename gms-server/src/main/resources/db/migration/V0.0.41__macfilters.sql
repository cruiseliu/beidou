-- macfilters：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `macfilters`
(
    `macfilterid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `filter`      VARCHAR(30)      NOT NULL

);
