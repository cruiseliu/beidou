CREATE TABLE IF NOT EXISTS `savedlocations`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `characterid`  INT(11) NOT NULL,
    `locationtype` TEXT            NOT NULL,
    `map`          INT(11) NOT NULL,
    `portal`       INT(11) NOT NULL

);
