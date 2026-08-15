CREATE TABLE IF NOT EXISTS `marriages`
(
    `marriageid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `husbandid`  INT(10) NOT NULL DEFAULT '0',
    `wifeid`     INT(10) NOT NULL DEFAULT '0'

);
