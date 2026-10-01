-- petignores（petid, itemid）并入 pets_json.data 的 excludes 数组（PetData 载体），
-- 过滤清单随宠物本体持久化；petignores 表删除。
UPDATE `pets_json` SET `data` = json_set(`data`, '$.excludes',
               (SELECT json_group_array(`itemid`) FROM `petignores` i WHERE i.`petid` = `pets_json`.`petid`))
WHERE EXISTS (SELECT 1 FROM `petignores` i WHERE i.`petid` = `pets_json`.`petid`);

DROP TABLE `petignores`;
