-- family_entitlement：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `family_entitlement`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid`        INT(11)    NOT NULL,
    `entitlementid` INT(11)    NOT NULL,
    `TIMESTAMP`     BIGINT(20) NOT NULL DEFAULT '0'

);
CREATE INDEX `family_entitlement_idx1` ON `family_entitlement` (charid);
