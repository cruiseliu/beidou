-- marriages：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `marriages`
(
    `marriageid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `husbandid`  INT(10) NOT NULL DEFAULT '0',
    `wifeid`     INT(10) NOT NULL DEFAULT '0'

);
