-- 宠物生命周期归属 pet 模块（doc/11）：到期时间与活跃状态由 pets 表承载，
-- 宿主物品 item.expiration 不再承载宠物到期（发放时恒 -1，inventory 过期任务不再检查宠物）。
-- expires_at: 到期 epoch 毫秒（-1 = 永久）
-- active:     1 = 活跃，0 = 失活（到期转化的宿主态，pet 数据与 petId 引用保留）
ALTER TABLE `pets` ADD COLUMN `expires_at` BIGINT NOT NULL DEFAULT -1;
ALTER TABLE `pets` ADD COLUMN `active` TINYINT(1) NOT NULL DEFAULT 1;
