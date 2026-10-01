package org.gms.client.weaponType;

/**
 * 武器攻击动作类型。
 *
 * 每种武器有 2 种近战动作（砍/刺）+ 部分远程武器额外的射击动作：
 * - SWING：砍（挥砍，单手剑/斧/锤、双手剑/斧/锤、矛 swing、枪 swing 等）
 * - STAB：刺（单手剑/斧/锤 stab、矛 stab、枪 stab 等）
 * - SHOOT：射（弓/弩/拳套/短枪的远程攻击）
 *
 * 对应客户端 Character.wz 动画：swingO1/swingT1（砍）、stabO1/stabT1（刺）、shoot1（射）。
 * 各动作伤害系数不同，见 WeaponTypeDefinition.actions。
 */
public enum WeaponAction {
    SWING,
    STAB,
    SHOOT
}
