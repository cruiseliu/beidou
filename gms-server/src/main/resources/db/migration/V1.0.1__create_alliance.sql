CREATE TABLE IF NOT EXISTS `alliance`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `name`     VARCHAR(13)      NOT NULL,
    `capacity` INT(10) NOT NULL DEFAULT '2',
    `notice`   VARCHAR(20)      NOT NULL DEFAULT '',
    `rank1`    VARCHAR(11)      NOT NULL DEFAULT 'Master',
    `rank2`    VARCHAR(11)      NOT NULL DEFAULT 'Jr. Master',
    `rank3`    VARCHAR(11)      NOT NULL DEFAULT 'Member',
    `rank4`    VARCHAR(11)      NOT NULL DEFAULT 'Member',
    `rank5`    VARCHAR(11)      NOT NULL DEFAULT 'Member'

);
CREATE INDEX IF NOT EXISTS `alliance_idx1` ON `alliance` (name);



CREATE TABLE IF NOT EXISTS `allianceguilds`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `allianceid` INT(10)          NOT NULL DEFAULT '-1',
    `guildid`    INT(10)          NOT NULL DEFAULT '-1'

);
