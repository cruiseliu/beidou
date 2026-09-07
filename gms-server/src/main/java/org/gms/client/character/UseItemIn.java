package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.net.server.Server;
import org.gms.remote.InventoryModule;
import org.gms.remote.in.events.UseItemEvent;
import org.gms.client.pet.Pet;
import org.gms.scripting.item.ItemScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 背包域收包入口：道具使用编排（原 PET_FOOD handler 的 gameplay 半边迁入）。
 * 在 player strand 上执行（in 管线回调），读自己的 autoban/背包/宠物状态（no-peek 边界之内）。
 * 喂食本体在道具脚本钩子内（onUse），本类只做编排：频率检查 → 选宠 → 校验 → 合并域包住 hook+消耗。
 */
public final class UseItemIn implements InventoryModule.In {

    private static final Logger log = LoggerFactory.getLogger(UseItemIn.class);

    private final Character chr;

    public UseItemIn(Character chr) {
        this.chr = chr;
    }

    @Override
    public void useItem(UseItemEvent e) {
        var abm = chr.getAutoBanManager();
        if (abm.getLastSpam(2) + 500 > Server.getInstance().getCurrentTime()) {
            chr.getRemote().basic().unlockActions();
            return;
        }
        abm.spam(2);
        abm.setTimestamp(1, Server.getInstance().getCurrentTimestamp(), 3);

        if (!chr.hasSummonedPet()) {
            chr.getRemote().basic().unlockActions();
            return;
        }
        int previousFullness = 100;
        byte slot = 0;
        Pet[] pets = chr.LEGACY_getSummonSlots();
        for (byte i = 0; i < 3; i++) {
            if (pets[i] != null) {
                if (pets[i].getFullness() < previousFullness) {
                    slot = i;
                    previousFullness = pets[i].getFullness();
                }
            }
        }

        Pet pet = chr.getPet(slot);
        if (pet == null) {
            return;
        }

        ItemScript script = ItemScript.forItem(e.itemId());
        if (script == null || !script.hasHook(chr, ItemScript.HOOK_USE)) {
            log.error("Pet food {} missing onUse script", e.itemId());
            chr.getRemote().basic().unlockActions();
            return;
        }

        InventoryTab useInv = chr.getInventory(InventoryType.USE);
        useInv.lockInventory();
        try {
            ItemSlot itemSlot = useInv.getItem(e.slot());
            if (itemSlot.getItemId() != e.itemId()) {
                log.error("Mismatch item: character:{} slot:{} real:{} packet:{}", chr.getId(), e.slot(), itemSlot.getItemId(), e.itemId());
                chr.getRemote().basic().unlockActions();
                return;
            }

            // 合并域包住 hook + 消耗（与 UseCashItemHandler 的 hook 分支同款）：
            // 一次喂食的全部语义更新一次 flush
            try (var update = chr.getRemote().update()) {
                boolean success = script.invokeUse(chr, itemSlot.getItem());
                if (!success) {
                    chr.getRemote().basic().unlockActions();
                    return;
                }

                InventoryManipulator.removeFromSlot(chr.getClient(), InventoryType.USE, e.slot(), (short) 1, false);
            }
        } finally {
            useInv.unlockInventory();
        }
    }
}
