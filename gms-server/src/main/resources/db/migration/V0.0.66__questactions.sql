-- questactions：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `questactions`
(
    `questactionid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `questid`       INT(11)          NOT NULL DEFAULT '0',
    `status`        INT(11)          NOT NULL DEFAULT '0',
    `data`          BLOB             NOT NULL

);
