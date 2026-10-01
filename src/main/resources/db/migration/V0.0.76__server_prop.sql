-- server_prop：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE server_prop
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `prop_type`  int,
    `prop_code`  varchar(32),
    `prop_class` varchar(32),
    `prop_value` varchar(255),
    `prop_desc`  varchar(500)

);
