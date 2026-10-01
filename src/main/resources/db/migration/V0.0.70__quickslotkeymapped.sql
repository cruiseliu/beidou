-- quickslotkeymapped：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `quickslotkeymapped`
(
    `accountid` INT    NOT NULL,
    `keymap`    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`accountid`)

);
