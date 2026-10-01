-- eventstats：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `eventstats`
(
    `characterid` INT(11) NOT NULL,
    `name`        VARCHAR(11)      NOT NULL DEFAULT '0',
    `info`        INT(11)          NOT NULL,
    PRIMARY KEY (`characterid`)

);
