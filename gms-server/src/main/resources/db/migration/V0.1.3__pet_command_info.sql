-- @pet 指令（发放宠物，PetCommand）登记 command_info。
-- 运行时指令注册走 CommandService.loadCommands（按 clazz 在 gm{default_level} 包反射实例化），
-- 缺行则指令不存在。level 沿用 V1.12.1 测试环境固化（全部 level=0），default_level 保留代码原始等级。
INSERT INTO `command_info` (`syntax`, `level`, `enabled`, `clazz`, `default_level`)
VALUES ('pet', 0, 1, 'PetCommand', 2);
