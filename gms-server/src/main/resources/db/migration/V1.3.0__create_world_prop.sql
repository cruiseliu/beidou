drop table if exists world_prop;
create table if not exists world_prop
(
    `id`                   bigint primary key not null,
    `flag`                 tinyint default 0,
    `server_message`       varchar(255),
    `event_message`        varchar(255),
    `recommend_message`    varchar(255),
    `channel_size`         int,
    `exp_rate`             decimal(40, 3),
    `meso_rate`            decimal(40, 3),
    `drop_rate`            decimal(40, 3),
    `boss_drop_rate`       decimal(40, 3),
    `quest_rate`           decimal(40, 3),
    `fishing_rate`         decimal(40, 3),
    `travel_rate`          decimal(40, 3),
    `level_exp_rate`       decimal(40, 3),
    `quick_level`          decimal(40, 3),
    `quick_level_exp_rate` decimal(40, 3),
    `enabled`              tinyint default 0

);
