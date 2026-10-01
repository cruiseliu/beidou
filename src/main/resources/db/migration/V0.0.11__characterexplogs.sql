-- characterexplogs：<1.12.0 全部迁移的最终状态 dump（schema + 种子数据）
CREATE TABLE `characterexplogs`  (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
  `world_exp_rate` int(0) NULL DEFAULT NULL,
  `exp_coupon` int(0) NULL DEFAULT NULL,
  `gained_exp` bigint(0) NULL DEFAULT NULL,
  `current_exp` bigint(0) NULL DEFAULT NULL,
  `exp_gain_time` timestamp(0) NULL DEFAULT NULL,
  `charid` int(0) NULL DEFAULT NULL

);
