-- quest 持久化已迁 character_json 的 quests 域（CharacterQuestsData，
-- CharacterQuests.toData/applyData），权威数据随 saveCharToDB 的 character_json
-- upsert 保存、加载走 loadDataFromJson。三张 SQL 表下线。
DROP TABLE IF EXISTS `questprogress`;
DROP TABLE IF EXISTS `medalmaps`;
DROP TABLE IF EXISTS `queststatus`;
