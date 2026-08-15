CREATE TABLE IF NOT EXISTS `family_character`
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
CREATE INDEX IF NOT EXISTS `family_character_idx1` ON `family_character` (cid, familyid);



CREATE TABLE IF NOT EXISTS `family_entitlement`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid`        INT(11)    NOT NULL,
    `entitlementid` INT(11)    NOT NULL,
    `TIMESTAMP`     BIGINT(20) NOT NULL DEFAULT '0'

);
CREATE INDEX IF NOT EXISTS `family_entitlement_idx1` ON `family_entitlement` (charid);
