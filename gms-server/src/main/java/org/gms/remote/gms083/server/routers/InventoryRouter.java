package org.gms.remote.gms083.server.routers;

import org.gms.client.pet.Pet;
import org.gms.remote.ServerEvent;
import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.events.FrozenInventoryEvent;
import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.inventory.server.InventoryFullEvent;
import org.gms.remote.modules.inventory.server.InventoryModsEvent;
import org.gms.remote.modules.inventory.server.SlotChange;
import org.gms.remote.modules.pet.server.PetSnap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 背包域 route：出脸继承自 {@link InventoryModule}（API → 事件在基类），本类承载统一冻结门
 * + emit + deliver + flush。freeze（宠物槽位快照 PetSnap，含行缺失 desync 容忍）经统一门
 * 拦截 InventoryModsEvent 在入域时点完成；client 注入用于宠物解析。
 */
public final class InventoryRouter extends InventoryModule implements ServerEventDest {

    private static final Logger log = LoggerFactory.getLogger(InventoryRouter.class);

    private final Gms083 client;

    public InventoryRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    /** 统一冻结门：宠物槽位的 body 入域前补齐为 PetSnap 快照，翻译层只读快照（其余事件恒等通过） */
    @Override
    protected ServerEventBase freeze(ServerEvent event) {
        if (!(event instanceof InventoryModsEvent(var changes))) {
            return event;
        }
        boolean hasPet = changes.stream().anyMatch(c ->
                c instanceof SlotChange.Added a && a.item().getPetId() > -1);
        if (!hasPet) {
            return new InventoryModsEvent(changes);
        }
        List<FrozenInventoryEvent.Element> elements = changes.stream().map(c -> {
            if (c instanceof SlotChange.Added a && a.item().getPetId() > -1) {
                Pet pet = resolvePet(a.item().getPetId());
                if (pet == null) {
                    return (FrozenInventoryEvent.Element) new FrozenInventoryEvent.Element.Passthrough(c);
                }
                return (FrozenInventoryEvent.Element) new FrozenInventoryEvent.Element.PetBody(
                        (short) a.position(), a.item().getItemId(),
                        new PetSnap(pet.getPetId(), pet.getName(), pet.getLevel(),
                                pet.getTameness(), pet.getFullness(), pet.getFlags(),
                                pet.isAlive(), pet.getExpiration()));
            }
            return (FrozenInventoryEvent.Element) new FrozenInventoryEvent.Element.Passthrough(c);
        }).toList();
        return new FrozenInventoryEvent(elements);
    }

    /** 宠物解析（沿用 forceUpdateItem 的自愈语义：驻留位查无则 DB 兜底）；行缺失（desync）→ null，冻结侧按无宠物处理 */
    private Pet resolvePet(int petId) {
        Pet pet = client.getLegacyClient().getPlayer().getPetById(petId);
        if (pet != null) {
            return pet;
        }
        try {
            return Pet.load(petId);
        } catch (RuntimeException e) {
            log.warn("宠物 {} 行缺失，按无宠物处理（desync 容忍）", petId, e);
            return null;
        }
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case InventoryModsEvent(var changes) -> client.translators().inventoryT.onInventoryMods(changes);
            case InventoryFullEvent fe -> client.translators().inventoryT.onInventoryFull();
            case FrozenInventoryEvent f -> client.translators().inventoryT.onFrozenInventory(f);
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
        client.translators().inventoryT.flush().forEach(client::send);
    }
}
