-- trocklocations：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `trocklocations`
(
    `trockid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11) NOT NULL,
    `mapid`       INT(11) NOT NULL,
    `vip`         INT(2)  NOT NULL

);
