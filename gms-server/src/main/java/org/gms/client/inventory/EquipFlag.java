package org.gms.client.inventory;

import org.gms.constants.inventory.ItemConstants;

import java.util.EnumSet;

/**
 * 装备专属旗标（挂在 Equip 组件上，与 {@link ItemFlag} 分离存储）。
 * legacy 位值仅用于客户端协议与存档格式兼容——服务端逻辑一律经本枚举。
 */
public enum EquipFlag {
    /** 防滑钉（雪鞋卷；wz info/fs>0 构造期落位；legacy 位 0x02 与 ItemFlag.KARMA_USE 共享） */
    SPIKES(ItemConstants.SPIKES),
    /** 防冻（防冻卷） */
    COLD(ItemConstants.COLD);

    private final int legacyValue;

    EquipFlag(int legacyValue) {
        this.legacyValue = legacyValue;
    }

    /** 客户端协议/存档格式的位值；除拼装点外禁止使用 */
    public int legacyValue() {
        return legacyValue;
    }

    static void collectFromLegacy(int raw, EnumSet<EquipFlag> into) {
        for (EquipFlag f : values()) {
            if ((raw & f.legacyValue) == f.legacyValue) {
                into.add(f);
            }
        }
    }
}
