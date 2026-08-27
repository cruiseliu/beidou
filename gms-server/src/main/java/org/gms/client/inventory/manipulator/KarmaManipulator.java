package org.gms.client.inventory.manipulator;

import org.gms.client.inventory.ItemFlag;
import org.gms.client.inventory.ItemSlot;

/**
 * 宿命剪刀（Karma Scissors）语义工具。两个实例标签、三条覆盖规则：
 * <ul>
 *   <li>{@code SCISSOR_USABLE}（恒定，wz tradeAvailable 落位）：该物品允许被剪</li>
 *   <li>{@code TRADE_ONCE}（瞬态）："可交易一次"，覆盖 {@code UNTRADEABLE} 与
 *       {@code SCISSOR_USABLE}——置位期间可交易且不可再剪</li>
 * </ul>
 * 成交出口必须调用 {@link #onTradeCompleted} 复位（Trade/PlayerShop/HiredMerchant/
 * Wedding/Storage/Duey 六类）。legacy 位映射在组装点按类别完成。
 */
public class KarmaManipulator {
    private KarmaManipulator() {
    }

    /** 是否处于"可交易一次"状态 */
    public static boolean isTradeOnceUnlocked(ItemSlot item) {
        return item.hasFlag(ItemFlag.TRADE_ONCE);
    }

    /** 使用宿命剪刀成功：进入"可交易一次"（UNTRADEABLE 保持置位，由覆盖规则放行） */
    public static void applyScissors(ItemSlot item) {
        item.addFlag(ItemFlag.TRADE_ONCE);
    }

    /** 成交完成：复位"可交易一次"，回到锁定态 */
    public static void onTradeCompleted(ItemSlot item) {
        item.removeFlag(ItemFlag.TRADE_ONCE);
    }
}
