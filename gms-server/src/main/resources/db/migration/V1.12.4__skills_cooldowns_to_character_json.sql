-- 技能等级与技能 CD 迁入 character_json（CharacterSkillsData 域），原表移除
DROP TABLE IF EXISTS `skills`;
DROP TABLE IF EXISTS `cooldowns`;
