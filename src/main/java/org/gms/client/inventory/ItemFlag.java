package org.gms.client.inventory;

import org.gms.constants.inventory.ItemConstants;

import java.util.EnumSet;

/**
 * 全类别物品的实例旗标（普通物品 + 装备共用的语义位）。
 * legacy 位值仅用于客户端协议与存档格式兼容——服务端逻辑一律经本枚举，
 * 禁止对 underlying int 直接做位运算（组装点见 V83RemoteClient 的逐位拼装）。
 */
public enum ItemFlag {
    /** 现金商城锁（到期自动解除；锁卷只作用于装备，但位本身类别无关） */
    LOCK(ItemConstants.LOCK),
    /** 宿命剪刀："可使用宿命剪刀"（wz info/tradeAvailable>0，构造期落位，恒定标签）。
     *  客户端协议无对应位——不参与 legacy 组装/分桶。 */
    SCISSOR_USABLE(0),
    /** 宿命剪刀："可交易一次"（使用剪刀时置位；成交时复位）。
     *  覆盖规则：置位时覆盖 UNTRADEABLE 与 SCISSOR_USABLE（不可再剪、可交易一次）。
     *  legacy 出口按类别映射：装备→0x10(KARMA_EQP)，其余→0x02(KARMA_USE)。 */
    TRADE_ONCE(0),
    /** 不可交易（wz tradeBlock 落位 / GM 烧位） */
    UNTRADEABLE(ItemConstants.UNTRADEABLE),
    /** GM 沙盒投放标记 */
    SANDBOX(ItemConstants.SANDBOX),
    /** 宠物随叫随到（宠物技能写入） */
    PET_COME(ItemConstants.PET_COME),
    /** 账号共享（wz accountSharable 落位 / GM 烧位） */
    ACCOUNT_SHARING(ItemConstants.ACCOUNT_SHARING);

    /** 旧 KARMA_USE/KARMA_EQP 位值（仅组装点引用） */
    public static final int LEGACY_KARMA_USE = 0x02;
    public static final int LEGACY_KARMA_EQP = 0x10;

    private final int legacyValue;

    ItemFlag(int legacyValue) {
        this.legacyValue = legacyValue;
    }

    /** 客户端协议/存档格式的位值；除拼装点外禁止使用（值为 0 表示无固定位映射） */
    public int legacyValue() {
        return legacyValue;
    }

    /** 从旧整型旗标识别本类位；karma 位（0x02/0x10）→ TRADE_ONCE，SCISSOR_USABLE 不从存档恢复 */
    static void collectFromLegacy(int raw, boolean equipType, EnumSet<ItemFlag> into) {
        if (!equipType && (raw & LEGACY_KARMA_USE) != 0) {
            into.add(TRADE_ONCE);
        }
        if (equipType && (raw & LEGACY_KARMA_EQP) != 0) {
            into.add(TRADE_ONCE);
        }
        for (ItemFlag f : values()) {
            if (f.legacyValue == 0 || f == TRADE_ONCE) {
                continue;   // 特判处理过 / 无固定位
            }
            if ((raw & f.legacyValue) == f.legacyValue) {
                into.add(f);
            }
        }
    }
}
