-- alliance：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `alliance`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `name`     VARCHAR(13)      NOT NULL,
    `capacity` INT(10) NOT NULL DEFAULT '2',
    `notice`   VARCHAR(20)      NOT NULL DEFAULT '',
    `rank1`    VARCHAR(11)      NOT NULL DEFAULT 'Master',
    `rank2`    VARCHAR(11)      NOT NULL DEFAULT 'Jr. Master',
    `rank3`    VARCHAR(11)      NOT NULL DEFAULT 'Member',
    `rank4`    VARCHAR(11)      NOT NULL DEFAULT 'Member',
    `rank5`    VARCHAR(11)      NOT NULL DEFAULT 'Member'

);
CREATE INDEX `alliance_idx1` ON `alliance` (name);
