-- bbs_replies：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `bbs_replies`
(
    `replyid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `threadid`  INT(10)    NOT NULL,
    `postercid` INT(10)    NOT NULL,
    `TIMESTAMP` BIGINT(20) NOT NULL,
    `content`   VARCHAR(26)         NOT NULL DEFAULT ''

);
