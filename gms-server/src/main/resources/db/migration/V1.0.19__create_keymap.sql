CREATE TABLE IF NOT EXISTS `keymap`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid` INT(11) NOT NULL DEFAULT '0',
    `key`         INT(11) NOT NULL DEFAULT '0',
    `type`        INT(11) NOT NULL DEFAULT '0',
    `action`      INT(11) NOT NULL DEFAULT '0'

);
