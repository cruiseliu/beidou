-- 测试环境固化：所有指令 level=0（普通玩家可用），清库重建后无需再手工调整。
-- default_level 保留代码原始等级，需要恢复权限体系时按 default_level 回填即可。
UPDATE `command_info` SET `level` = 0;
