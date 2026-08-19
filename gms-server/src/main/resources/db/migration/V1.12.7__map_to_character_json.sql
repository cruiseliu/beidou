-- mapId 迁入 character_json（CharacterData.mapId 域），characters 表移除 map 列
ALTER TABLE `characters` DROP COLUMN `map`;
