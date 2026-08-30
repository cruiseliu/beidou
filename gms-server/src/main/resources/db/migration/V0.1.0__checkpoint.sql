-- checkpoint：V1.12.0 ~ V1.13.1 的合并重放（按原顺序串联，等价于逐版本执行；
-- 内容：character_json 域迁移 / 指令与测试环境配置 / pets_json / petignores 去外键 / drop pets）

-- ============================== V1.12.0__create_character_json.sql ==============================
CREATE TABLE IF NOT EXISTS `character_json`
(
    `id`   INTEGER PRIMARY KEY,
    `data` TEXT NOT NULL
);

-- str/dex/luk/int/hp/mp/maxhp/maxmp 迁入 character_json，从 characters 表移除
ALTER TABLE `characters` DROP COLUMN `str`;
ALTER TABLE `characters` DROP COLUMN `dex`;
ALTER TABLE `characters` DROP COLUMN `luk`;
ALTER TABLE `characters` DROP COLUMN `int`;
ALTER TABLE `characters` DROP COLUMN `hp`;
ALTER TABLE `characters` DROP COLUMN `mp`;
ALTER TABLE `characters` DROP COLUMN `maxhp`;
ALTER TABLE `characters` DROP COLUMN `maxmp`;

-- ============================== V1.12.1__lower_all_commands_level_to_0.sql ==============================
-- 测试环境固化：所有指令 level=0（普通玩家可用），清库重建后无需再手工调整。
-- default_level 保留代码原始等级，需要恢复权限体系时按 default_level 回填即可。
UPDATE `command_info` SET `level` = 0;

-- ============================== V1.12.2__test_env_full_drop_rates.sql ==============================
-- 测试环境固化：测试用掉率改为必掉（chance 单位为百万分比，1000000 = 100%）。
-- 蘑菇仔(120100) 蘑菇芽孢 4000011、蓝蜗牛(100101) 蜗牛壳 4000000：任务/拾取场景需要稳定产出；
-- 蓝蜗牛金币同步提高，方便掉落测试观察。
UPDATE `drop_data` SET `chance` = 1000000 WHERE `dropperid` = 120100 AND `itemid` = 4000011;
UPDATE `drop_data` SET `chance` = 1000000 WHERE `dropperid` = 100101 AND `itemid` = 4000000;
UPDATE `drop_data` SET `chance` = 1000000 WHERE `dropperid` = 100101 AND `itemid` = 0;

-- ============================== V1.12.3__test_env_game_config.sql ==============================
-- 测试环境固化：怪物全量重生 + 封包调试日志（原为手工调整，清库重建后即失效）。
-- 迁移在配置加载前执行，固化后首次启动即生效，无需再改库重启。
UPDATE `game_config` SET `config_value` = 'true' WHERE `config_code` = 'use_enable_full_respawn';
UPDATE `game_config` SET `config_value` = 'true' WHERE `config_code` = 'use_debug_show_packet';

-- ============================== V1.12.4__skills_cooldowns_to_character_json.sql ==============================
-- 技能等级与技能 CD 迁入 character_json（CharacterSkillsData 域），原表移除
DROP TABLE IF EXISTS `skills`;
DROP TABLE IF EXISTS `cooldowns`;

-- ============================== V1.12.5__ap_sp_to_character_json.sql ==============================
-- AP/SP 迁入 character_json（CharacterApData/CharacterSpData 域），characters 表移除三列
ALTER TABLE `characters` DROP COLUMN `ap`;
ALTER TABLE `characters` DROP COLUMN `hpMpUsed`;
ALTER TABLE `characters` DROP COLUMN `sp`;

-- ============================== V1.12.6__debuffs_to_character_json.sql ==============================
-- debuff 迁入 character_json（CharacterDebuffsData 域），原表移除
DROP TABLE IF EXISTS `playerdiseases`;

-- ============================== V1.12.7__map_to_character_json.sql ==============================
-- mapId 迁入 character_json（CharacterData.mapId 域），characters 表移除 map 列
ALTER TABLE `characters` DROP COLUMN `map`;

-- ============================== V1.12.8__anti_cheat_to_character_json.sql ==============================
-- 反作弊（监狱刑期）迁入 character_json（CharacterAntiCheatData 域），characters 表移除 jailexpire 列
ALTER TABLE `characters` DROP COLUMN `jailexpire`;

-- ============================== V1.13.0__pet_lifecycle_columns.sql ==============================
-- 宠物生命周期归属 pet 模块（doc/11）：到期时间与活跃状态由 pets 表承载，
-- 宿主物品 item.expiration 不再承载宠物到期（发放时恒 -1，inventory 过期任务不再检查宠物）。
-- expires_at: 到期 epoch 毫秒（-1 = 永久）
-- active:     1 = 活跃，0 = 失活（到期转化的宿主态，pet 数据与 petId 引用保留）
ALTER TABLE `pets` ADD COLUMN `expires_at` BIGINT NOT NULL DEFAULT -1;
ALTER TABLE `pets` ADD COLUMN `active` TINYINT(1) NOT NULL DEFAULT 1;

-- ============================== V1.13.1__pets_to_json.sql ==============================
-- 宠物持久化改单表 JSON（对齐 character_json 风格，doc/11）：
-- pets 表各列收进 pets_json.data（PetData 载体），petid 仍是主键（与 rings 共用 CashIdGenerator 号段）。
CREATE TABLE IF NOT EXISTS `pets_json`
(
    `petid` INTEGER PRIMARY KEY,
    `data`  TEXT NOT NULL
);

-- 存量迁移（测试环境通常清库重建，此处兜底）
INSERT INTO `pets_json` (`petid`, `data`)
SELECT `petid`, json_object('name', `name`, 'level', `level`, 'tameness', `closeness`,
                            'fullness', `fullness`, 'summoned', `summoned`, 'flag', `flag`,
                            'expiresAt', `expires_at`, 'active', `active`)
FROM `pets`;

-- petignores 的外键指向 pets，pets 表删除前重建为无外键同构表（级联清理已由代码显式删除承担）
CREATE TABLE `petignores_new`
(
    `id`     INTEGER PRIMARY KEY AUTOINCREMENT,
    `petid`  INT(11) NOT NULL,
    `itemid` INT(10) NOT NULL
);
INSERT INTO `petignores_new` (`id`, `petid`, `itemid`) SELECT `id`, `petid`, `itemid` FROM `petignores`;
DROP TABLE `petignores`;
ALTER TABLE `petignores_new` RENAME TO `petignores`;

DROP TABLE `pets`;
