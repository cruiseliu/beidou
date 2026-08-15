CREATE TABLE IF NOT EXISTS `area_info`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid` INT(11)      NOT NULL,
    `area`   INT(11)      NOT NULL,
    `info`   VARCHAR(200) NOT NULL

);
