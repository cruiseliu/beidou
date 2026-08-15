drop table if exists modified_cash_item;
create table modified_cash_item (
    `sn` int(11) not null,
    `item_id` int(11),
    `count` int(11),
    `price` int(11),
    `bonus` int(11),
    `priority` int(11),
    `period` bigint(20),
    `maple_point` int(11),
    `meso` int(11),
    `for_premium_user` int(11),
    `commodity_gender` int(11),
    `on_sale` int(1),
    `class` int(11),
    `limit` int(11),
    `pb_cash` int(11),
    `pb_point` int(11),
    `pb_gift` int(11),
    `package_sn` int(11),
    primary key (`sn`)

);


