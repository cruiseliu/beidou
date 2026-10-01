-- queststatus：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `queststatus`
(
    `queststatusid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`   INT(11)          NOT NULL DEFAULT '0',
    `quest`         INT(11)          NOT NULL DEFAULT '0',
    `status`        INT(11)          NOT NULL DEFAULT '0',
    `time`          INT(11)          NOT NULL DEFAULT '0',
    `expires`       BIGINT(20)       NOT NULL DEFAULT '0',
    `forfeited`     INT(11)          NOT NULL DEFAULT '0',
    `completed`     INT(11)          NOT NULL DEFAULT '0',
    `info`          TINYINT(3)       NOT NULL DEFAULT '0'

);
