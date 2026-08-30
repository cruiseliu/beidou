-- hwidaccounts：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `hwidaccounts`
(
    `accountid` INT(11)     NOT NULL DEFAULT '0',
    `hwid`      VARCHAR(40) NOT NULL DEFAULT '',
    `relevance` TINYINT(2)  NOT NULL DEFAULT '0',
    `expiresat` TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`accountid`, `hwid`)

);
