package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.Pet;
import org.gms.client.inventory.PetDataFactory;
import org.gms.constants.inventory.ItemConstants;
import org.gms.dao.entity.PetignoresDO;
import org.gms.manager.ServerManager;
import org.gms.service.InventoryService;
import org.gms.util.I18nUtil;
import org.gms.util.Locks;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * 宠物模块组件：三个宠物槽位 + 过滤配置（petignores 内存态）+ 拾取瞬移上下文 + 持久化。
 * 仿照 CharacterBuffs 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，自有锁（lock）串行化，
 * Character 保留公开具名门面（getPets/unEquipPet/... 对外转发）。
 *
 * 锁说明：pets 数组、excluded/excludedItems、拾取上下文共用本类 lock——
 * 原实现 pets 用 petLock、excluded 用 chrLock、且 petLock 还被 lastVisitedMaps 误用，
 * 重构时统一收敛（lastVisitedMaps 已在 Character 中改用自己的锁）。
 */
class CharacterPets {
    private static final InventoryService inventoryService = ServerManager.getApplicationContext().getBean(InventoryService.class);
    private static final long PET_LOOT_TELEPORT_CONTEXT_EXPIRE_NS = MILLISECONDS.toNanos(1500L);

    private final Character owner;

    /** 三个召唤槽位（沿用旧名 pets） */
    private final Pet[] pets = new Pet[3];

    /** 全量宠物：petid → Pet（角色负责管理；与物品只靠 petid 关联，互不存引用） */
    private final Map<Integer, Pet> allPets = new LinkedHashMap<>();

    /** 宠物模块锁：串行化槽位/过滤配置/拾取上下文 */
    private final Lock lock = new ReentrantLock(true);

    /** 宠物ID → 屏蔽道具ID集（内存态，增量同步到 petignores 表） */
    private final Map<Integer, Set<Integer>> excluded = new LinkedHashMap<>();

    /** 本角色已生效的屏蔽道具ID集（客户端加载列表用） */
    private final Set<Integer> excludedItems = new LinkedHashSet<>();

    /** 宠物拾取补偿用传送前坐标（1.5s 有效） */
    private Point petLootTeleportBeforePos = null;
    private long petLootTeleportBeforePosTime = 0;

    CharacterPets(Character owner) {
        this.owner = owner;
    }

    // ── 全量管理 ──

    /** 宠物登记（创建/加载时调用） */
    void registerPet(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            allPets.put(pet.getUniqueId(), pet);
        }
    }

    /** 按 petid 查询全量宠物（物品→宠物方向；未登记返回 null） */
    Pet getPetById(int petid) {
        try (var ignored = Locks.acquire(lock)) {
            return allPets.get(petid);
        }
    }

    /** 宠物注销（物品删除时调用；同步移出召唤槽） */
    void unregisterPet(int petid) {
        try (var ignored = Locks.acquire(lock)) {
            allPets.remove(petid);
            for (int i = 0; i < 3; i++) {
                if (pets[i] != null && pets[i].getUniqueId() == petid) {
                    pets[i] = null;
                }
            }
        }
    }

    /**
     * 登录加载：背包就绪后收集全部 petid，从 pets 表批量载入并恢复召唤槽
     * （pets 表无角色列，归属即背包持有；孤儿行不加载——与旧 Item 构造触发加载的语义一致）。
     */
    void loadPetsFromInventories() {
        List<Pet> summoned = new ArrayList<>();
        for (ItemSlot item : owner.getInventory(InventoryType.CASH).list()) {
            int petid = item.getPetId();
            if (petid < 0) {
                continue;
            }
            Pet pet = getPetById(petid);
            if (pet == null) {
                pet = Pet.loadFromDb(owner, item.getItemId(), petid);
                if (pet != null) {
                    registerPet(pet);
                }
            }
            if (pet != null && pet.isSummoned()) {
                summoned.add(pet);
            }
        }
        for (Pet pet : summoned) {
            if (getNoPets() >= 3) {
                break;
            }
            addPet(pet);
            owner.loadPetExcludedItems(pet.getUniqueId());
        }
    }

    // ── 槽位管理 ──

    void addPet(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            for (int i = 0; i < 3; i++) {
                if (pets[i] == null) {
                    pets[i] = pet;
                    return;
                }
            }
        }
    }

    void removePet(Pet pet, boolean shift_left) {
        try (var ignored = Locks.acquire(lock)) {
            int slot = -1;
            for (int i = 0; i < 3; i++) {
                if (pets[i] != null) {
                    if (pets[i].getUniqueId() == pet.getUniqueId()) {
                        pets[i] = null;
                        slot = i;
                        break;
                    }
                }
            }
            if (shift_left) {
                if (slot > -1) {
                    for (int i = slot; i < 3; i++) {
                        if (i != 2) {
                            pets[i] = pets[i + 1];
                        } else {
                            pets[i] = null;
                        }
                    }
                }
            }
        }
    }

    void shiftPetsRight() {
        try (var ignored = Locks.acquire(lock)) {
            if (pets[2] == null) {
                pets[2] = pets[1];
                pets[1] = pets[0];
                pets[0] = null;
            }
        }
    }

    int getNoPets() {
        try (var ignored = Locks.acquire(lock)) {
            int ret = 0;
            for (int i = 0; i < 3; i++) {
                if (pets[i] != null) {
                    ret++;
                }
            }
            return ret;
        }
    }

    Pet[] getPets() {
        try (var ignored = Locks.acquire(lock)) {
            return Arrays.copyOf(pets, pets.length);
        }
    }

    Pet getPet(int index) {
        if (index < 0) {
            return null;
        }
        try (var ignored = Locks.acquire(lock)) {
            return pets[index];
        }
    }

    byte getPetIndex(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            for (byte i = 0; i < 3; i++) {
                if (pets[i] != null) {
                    if (pets[i].getUniqueId() == petId) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

    byte getPetIndex(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            for (byte i = 0; i < 3; i++) {
                if (pets[i] != null) {
                    if (pets[i].getUniqueId() == pet.getUniqueId()) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

    // ── 装备配件（读 EQUIPPED 背包的宠物装备槽） ──

    int getPetEquipItemId(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return 0;
        }
        ItemSlot petEqp = owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).equip());
        return petEqp == null ? 0 : petEqp.getItemId();
    }

    boolean hasPetNameTag(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).nameTag()) != null;
    }

    boolean hasPetChatballoon(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).chatBalloon()) != null;
    }

    boolean isEquippedMesoMagnet(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).mesoMagnet()) != null;
    }

    boolean isEquippedItemPouch(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).itemPouch()) != null;
    }

    boolean isEquippedPetItemIgnore(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).itemIgnore()) != null;
    }

    // ── 生命周期 ──

    void unEquipAllPets() {
        for (int i = 0; i < 3; i++) {
            Pet pet = getPet(i);
            if (pet != null) {
                unEquipPet(pet, true);
            }
        }
    }

    void unEquipPet(Pet pet, boolean shift_left) {
        unEquipPet(pet, shift_left, false);
    }

    void unEquipPet(Pet pet, boolean shift_left, boolean hunger) {
        byte petIdx = getPetIndex(pet);
        Pet chrPet = getPet(petIdx);

        if (chrPet != null) {
            chrPet.setSummoned(false);
            chrPet.saveToDb();
        }

        owner.getClient().getWorldServer().unregisterPetHunger(owner, petIdx);
        owner.getMap().broadcastMessage(owner, PacketCreator.showPet(owner, pet, true, hunger), true);

        removePet(pet, shift_left);
        commitExcludedItems();

        owner.sendPacket(PacketCreator.petStatUpdate(owner));
        owner.enableActions();
    }

    void runFullnessSchedule(int petSlot) {
        Pet pet = getPet(petSlot);
        if (pet == null) {
            return;
        }

        int newFullness = pet.getFullness() - PetDataFactory.getHunger(pet.getItemId());
        if (newFullness <= 5) {
            pet.setFullness(15);
            pet.saveToDb();
            unEquipPet(pet, true);
            owner.dropMessage(6, I18nUtil.getMessage("Character.runFullnessSchedule"));
        } else {
            pet.setFullness(newFullness);
            pet.saveToDb();
            ItemSlot petz = owner.findPetItemSlot(pet.getUniqueId());
            if (petz != null) {
                owner.forceUpdateItem(petz);
            }
        }
    }

    // ── 过滤配置（petignores 内存态） ──

    void resetExcluded(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            Set<Integer> petExclude = excluded.get(petId);

            if (petExclude != null) {
                petExclude.clear();
            } else {
                excluded.put(petId, new LinkedHashSet<>());
            }
        }
    }

    void addExcluded(int petId, int x) {
        try (var ignored = Locks.acquire(lock)) {
            excluded.get(petId).add(x);
        }
    }

    /**
     * 统一从数据库加载单只宠物的过滤配置，确保召唤时内存状态与数据库保持一致。
     */
    void loadPetExcludedItems(int petId) {
        List<Integer> excludedItemIds = inventoryService.getPetIgnoreByPetId(petId).stream()
                .map(PetignoresDO::getItemid)
                .filter(Objects::nonNull)
                .toList();
        replacePetExcludedItemsInMemory(petId, excludedItemIds);
    }

    /** 客户端提交过滤设置时，直接按差异增量更新数据库，避免角色保存时再做危险的全量删写。 */
    void updatePetExcludedItems(int petId, Set<Integer> newExcludedItems) {
        Set<Integer> currentExcludedItems = getExcludedForPet(petId);
        Set<Integer> normalizedExcludedItems = new LinkedHashSet<>(newExcludedItems);

        Set<Integer> toAdd = new LinkedHashSet<>(normalizedExcludedItems);
        toAdd.removeAll(currentExcludedItems);

        Set<Integer> toRemove = new LinkedHashSet<>(currentExcludedItems);
        toRemove.removeAll(normalizedExcludedItems);

        inventoryService.addPetIgnoreItems(petId, toAdd);
        inventoryService.removePetIgnoreItems(petId, toRemove);
        replacePetExcludedItemsInMemory(petId, normalizedExcludedItems);
    }

    /** 宠物被永久删除时同步清理数据库和角色内存中的过滤配置，避免残留脏数据。 */
    void deletePetExcludedData(int petId) {
        inventoryService.deletePetData(petId);
        removeExcluded(petId);
    }

    Set<Integer> getExcludedForPet(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            Set<Integer> petExcludedItems = excluded.get(petId);
            if (petExcludedItems == null) {
                return Collections.emptySet();
            }
            return Collections.unmodifiableSet(new LinkedHashSet<>(petExcludedItems));
        }
    }

    Map<Integer, Set<Integer>> getExcluded() {
        try (var ignored = Locks.acquire(lock)) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(excluded));
        }
    }

    Set<Integer> getExcludedItems() {
        try (var ignored = Locks.acquire(lock)) {
            return Collections.unmodifiableSet(excludedItems);
        }
    }

    void commitExcludedItems() {
        Map<Integer, Set<Integer>> petExcluded = getExcluded();

        try (var ignored = Locks.acquire(lock)) {
            excludedItems.clear();
        }

        for (Map.Entry<Integer, Set<Integer>> pe : petExcluded.entrySet()) {
            byte petIndex = getPetIndex(pe.getKey());
            if (petIndex < 0) {
                continue;
            }

            Set<Integer> exclItems = pe.getValue();
            if (!exclItems.isEmpty()) {
                owner.sendPacket(PacketCreator.loadExceptionList(owner.getId(), pe.getKey(), petIndex, new ArrayList<>(exclItems)));

                try (var ignored = Locks.acquire(lock)) {
                    excludedItems.addAll(exclItems);
                }
            }
        }
    }

    void exportExcludedItems(Client c) {
        Map<Integer, Set<Integer>> petExcluded = getExcluded();
        for (Map.Entry<Integer, Set<Integer>> pe : petExcluded.entrySet()) {
            byte petIndex = getPetIndex(pe.getKey());
            if (petIndex < 0) {
                continue;
            }

            Set<Integer> exclItems = pe.getValue();
            if (!exclItems.isEmpty()) {
                c.sendPacket(PacketCreator.loadExceptionList(owner.getId(), pe.getKey(), petIndex, new ArrayList<>(exclItems)));
            }
        }
    }

    private void replacePetExcludedItemsInMemory(int petId, Collection<Integer> itemIds) {
        try (var ignored = Locks.acquire(lock)) {
            excluded.remove(petId);
            if (itemIds != null && !itemIds.isEmpty()) {
                LinkedHashSet<Integer> normalizedItems = itemIds.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (!normalizedItems.isEmpty()) {
                    excluded.put(petId, normalizedItems);
                }
            }
        }
    }

    private void removeExcluded(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            excluded.remove(petId);
        }
    }

    // ── 拾取瞬移上下文 ──

    /**
     * 记录传送前玩家坐标，供宠物拾取反作弊旧位置物品补偿使用。
     * 每次内传送门触发时由 InnerPortalHandler 调用。
     */
    void setPetLootTeleportBeforePos(Point pos) {
        this.petLootTeleportBeforePos = pos;
        this.petLootTeleportBeforePosTime = monotonicNow();
    }

    /** 获取宠物拾取补偿用的传送前坐标。1.5s 内有效，超时自动失效，避免旧坐标残留下一次捡包误判。 */
    Point getPetLootTeleportBeforePos() {
        if (petLootTeleportBeforePos == null) {
            return null;
        }
        if (monotonicNow() - petLootTeleportBeforePosTime > PET_LOOT_TELEPORT_CONTEXT_EXPIRE_NS) {
            petLootTeleportBeforePos = null;
            return null;
        }
        return new Point(petLootTeleportBeforePos);
    }

    private static long monotonicNow() {
        return System.nanoTime();
    }

    // ── 持久化 ──

    /** 角色保存主事务内调用：用传入连接保存全部宠物（消除第二写者，见 Pet.saveToDb(Connection)） */
    void saveToDb(Connection con) {
        List<Pet> petList;
        try (var ignored = Locks.acquire(lock)) {
            petList = new LinkedList<>(allPets.values());
        }

        for (Pet pet : petList) {
            pet.saveToDb(con);
        }
    }
}
