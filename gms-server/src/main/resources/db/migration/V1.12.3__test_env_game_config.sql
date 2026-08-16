-- 测试环境固化：怪物全量重生 + 封包调试日志（原为手工调整，清库重建后即失效）。
-- 迁移在配置加载前执行，固化后首次启动即生效，无需再改库重启。
UPDATE `game_config` SET `config_value` = 'true' WHERE `config_code` = 'use_enable_full_respawn';
UPDATE `game_config` SET `config_value` = 'true' WHERE `config_code` = 'use_debug_show_packet';
