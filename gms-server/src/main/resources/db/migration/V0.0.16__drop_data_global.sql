-- drop_data_global：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `drop_data_global`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `continent`        TINYINT(1) NOT NULL DEFAULT '-1',
    `itemid`           INT(11)    NOT NULL DEFAULT '0',
    `minimum_quantity` INT(11)    NOT NULL DEFAULT '1',
    `maximum_quantity` INT(11)    NOT NULL DEFAULT '1',
    `questid`          INT(11)    NOT NULL DEFAULT '0',
    `chance`           INT(11)    NOT NULL DEFAULT '0',
    `comments`         VARCHAR(45)         DEFAULT NULL

);
CREATE INDEX `drop_data_global_mobid` ON `drop_data_global` (`continent`);
INSERT INTO `drop_data_global` (`id`, `continent`, `itemid`, `minimum_quantity`, `maximum_quantity`, `questid`, `chance`, `comments`) VALUES
(1, -1, 4031865, 1, 1, 0, 35000, 'NX Card 100 PTS'),
(2, -1, 4031866, 1, 1, 0, 20000, 'NX Card 250 PTS'),
(3, -1, 4001126, 1, 2, 0, 8000, 'Maple Leaves'),
(4, -1, 2049100, 1, 1, 0, 1200, 'Chaos Scroll 60%'),
(5, -1, 2340000, 1, 1, 0, 1200, 'White Scroll'),
(6, -1, 4001006, 1, 1, 0, 10000, 'Flaming Feather');
