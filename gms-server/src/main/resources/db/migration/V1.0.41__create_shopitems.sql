CREATE TABLE IF NOT EXISTS `shopitems`
(
    `shopitemid` INTEGER PRIMARY KEY AUTOINCREMENT,
    `shopid`     INT(10) NOT NULL,
    `itemid`     INT(11)          NOT NULL,
    `price`      INT(11)          NOT NULL,
    `pitch`      INT(11)          NOT NULL DEFAULT '0',
    `position`   INT(11)          NOT NULL

);
