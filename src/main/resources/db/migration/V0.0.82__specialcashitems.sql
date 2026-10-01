-- specialcashitems：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `specialcashitems`
(
    `id`       INT(11) NOT NULL,
    `sn`       INT(11) NOT NULL,
    `modifier` INT(11) NOT NULL,
    `info`     INT(1)  NOT NULL,
    PRIMARY KEY (`id`)

);
INSERT INTO `specialcashitems` (`id`, `sn`, `modifier`, `info`) VALUES
(1, 10000617, 1024, 1);
