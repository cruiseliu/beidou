-- family_character：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `family_character`
(
    `cid`             INT(11)    NOT NULL,
    `familyid`        INT(11)    NOT NULL,
    `seniorid`        INT(11)    NOT NULL,
    `reputation`      INT(11)    NOT NULL DEFAULT '0',
    `todaysrep`       INT(11)    NOT NULL DEFAULT '0',
    `totalreputation` INT(11)    NOT NULL DEFAULT '0',
    `reptosenior`     INT(11)    NOT NULL DEFAULT '0',
    `precepts`        VARCHAR(200)        DEFAULT NULL,
    `lastresettime`   BIGINT(20) NOT NULL DEFAULT '0',
    PRIMARY KEY (`cid`)

);
CREATE INDEX `family_character_idx1` ON `family_character` (cid, familyid);
