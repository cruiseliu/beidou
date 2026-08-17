-- AP/SP 迁入 character_json（CharacterApData/CharacterSpData 域），characters 表移除三列
ALTER TABLE `characters` DROP COLUMN `ap`;
ALTER TABLE `characters` DROP COLUMN `hpMpUsed`;
ALTER TABLE `characters` DROP COLUMN `sp`;
