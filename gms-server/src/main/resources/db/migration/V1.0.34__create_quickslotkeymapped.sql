-- &
CREATE TABLE IF NOT EXISTS `quickslotkeymapped`
(
    `accountid` INT    NOT NULL,
    `keymap`    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`accountid`)

);


-- 已丢弃 1 条 ALTER ADD CONSTRAINT 外键（SQLite 不支持且默认不启用外键）