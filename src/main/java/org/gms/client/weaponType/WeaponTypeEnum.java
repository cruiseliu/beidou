package org.gms.client.weaponType;

/**
 * 武器类型枚举（替代整数 typeId 段号）。
 *
 * 命名规范：
 * - 区分单双手的武器：ONE_HANDED_* / TWO_HANDED_*（如 ONE_HANDED_SWORD / TWO_HANDED_SWORD）
 * - 钝器统一叫 BLUNT_WEAPON（如 ONE_HANDED_BLUNT_WEAPON / TWO_HANDED_BLUNT_WEAPON）
 * - 无单双手区分的武器直接用具名（DAGGER / WAND / STAFF / SPEAR / POLEARM / BOW / CROSSBOW / CLAW / KNUCKLE / GUN）
 *
 * 每个枚举对应一个武器类型定义（data/weapon_type/*.json），typeId 为物品段号（itemId/10000%100）。
 */
public enum WeaponTypeEnum {
    ONE_HANDED_SWORD(30),
    ONE_HANDED_AXE(31),
    ONE_HANDED_BLUNT_WEAPON(32),
    DAGGER(33),
    WAND(37),
    STAFF(38),
    TWO_HANDED_SWORD(40),
    TWO_HANDED_AXE(41),
    TWO_HANDED_BLUNT_WEAPON(42),
    SPEAR(43),
    POLEARM(44),
    BOW(45),
    CROSSBOW(46),
    CLAW(47),
    KNUCKLE(48),
    GUN(49);

    private final int typeId;

    WeaponTypeEnum(int typeId) {
        this.typeId = typeId;
    }

    /** 物品段号（itemId/10000%100） */
    public int getTypeId() {
        return typeId;
    }
}
