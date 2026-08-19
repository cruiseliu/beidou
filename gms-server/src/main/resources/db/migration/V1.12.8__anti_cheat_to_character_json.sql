-- 反作弊（监狱刑期）迁入 character_json（CharacterAntiCheatData 域），characters 表移除 jailexpire 列
ALTER TABLE `characters` DROP COLUMN `jailexpire`;
