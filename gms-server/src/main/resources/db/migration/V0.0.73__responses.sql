-- responses：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `responses`
(
    `chat`     TEXT,
    `response` TEXT,
    `id` INTEGER PRIMARY KEY AUTOINCREMENT

);
