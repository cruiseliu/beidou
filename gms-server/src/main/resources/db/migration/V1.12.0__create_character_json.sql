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
