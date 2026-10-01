-- wishlists：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `wishlists`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid` INT(11) NOT NULL,
    `sn`     INT(11) NOT NULL

);
CREATE INDEX `wishlists_idx_charid` ON `wishlists` (`charid`);
