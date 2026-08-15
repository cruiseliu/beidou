CREATE TABLE IF NOT EXISTS `playerdiseases`
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `charid`     INT(11) NOT NULL,
    `disease`    INT(11) NOT NULL,
    `mobskillid` INT(11) NOT NULL,
    `mobskilllv` INT(11) NOT NULL,
    `length`     INT(11) NOT NULL DEFAULT '1'

);
