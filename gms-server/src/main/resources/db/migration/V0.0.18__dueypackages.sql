-- dueypackages：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `dueypackages`
(
    `PackageId` INTEGER PRIMARY KEY AUTOINCREMENT,
    `ReceiverId` INT(10) NOT NULL,
    `SenderName` VARCHAR(13)      NOT NULL,
    `Mesos`      INT(10)          DEFAULT '0',
    `TIMESTAMP`  TIMESTAMP        NOT NULL DEFAULT '2015-01-01 05:00:00',
    `Message`    VARCHAR(200)     NULL,
    `Checked`    TINYINT(1)       DEFAULT '1',
    `Type`       TINYINT(1)       DEFAULT '0'

);
