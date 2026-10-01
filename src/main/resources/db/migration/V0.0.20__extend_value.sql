-- extend_value：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE extend_value(
    extend_id varchar(50) not null,
    extend_type int not null,
    extend_name varchar(50) not null,
    extend_value varchar(255),
    create_time datetime not null default current_timestamp,
    update_time datetime,
    primary key (extend_id, extend_type, extend_name)

);
