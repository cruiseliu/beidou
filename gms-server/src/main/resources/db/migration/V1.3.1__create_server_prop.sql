drop table if exists server_prop;
create table if not exists server_prop
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `prop_type`  int,
    `prop_code`  varchar(32),
    `prop_class` varchar(32),
    `prop_value` varchar(255),
    `prop_desc`  varchar(500)

);
