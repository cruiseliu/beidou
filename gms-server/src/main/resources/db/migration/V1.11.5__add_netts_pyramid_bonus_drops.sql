INSERT INTO `drop_data` (`dropperid`, `itemid`, `minimum_quantity`, `maximum_quantity`, `questid`, `chance`)
VALUES
    (9700019, 2022613, 1, 1, 0, 1000000),
    (9700029, 2022618, 1, 1, 0, 1000000) ON CONFLICT(`dropperid`, `itemid`) DO UPDATE SET `minimum_quantity` = excluded.`minimum_quantity`,
    `maximum_quantity` = excluded.`maximum_quantity`,
    `questid` = excluded.`questid`,
    `chance` = excluded.`chance`;
